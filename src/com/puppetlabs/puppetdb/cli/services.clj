;; ## Main entrypoint
;;
;; PuppetDB consists of several, cooperating components:
;;
;; * Command processing
;;
;;   PuppetDB uses a CQRS pattern for making changes to its domain
;;   objects (facts, catalogs, etc). Instead of simply submitting data
;;   to PuppetDB and having it figure out the intent, the intent
;;   needs to explicitly be codified as part of the operation. This is
;;   known as a "command" (e.g. "replace the current facts for node
;;   X").
;;
;;   Commands are processed asynchronously, however we try to do our
;;   best to ensure that once a command has been accepted, it will
;;   eventually be executed. Ordering is also preserved. To do this,
;;   all incoming commands are placed in a message queue which the
;;   command processing subsystem reads from in FIFO order.
;;
;;   Refer to `com.puppetlabs.puppetdb.command` for more details.
;;
;; * Message queue
;;
;;   We use an embedded instance of AciveMQ to handle queueing duties
;;   for the command processing subsystem. The message queue is
;;   persistent, and it only allows connections from within the same
;;   VM.
;;
;;   Refer to `com.puppetlabs.mq` for more details.
;;
;; * REST interface
;;
;;   All interaction with PuppetDB is conducted via its REST API. We
;;   embed an instance of Jetty to handle web server duties. Commands
;;   that come in via REST are relayed to the message queue. Read-only
;;   requests are serviced synchronously.
;;
;; * Database garbage collector
;;
;;   As catalogs are modified, unused records may accumulate in the
;;   database. We periodically compact the database to maintain
;;   acceptable performance.
;;
(ns com.puppetlabs.puppetdb.cli.services
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.scf.migrate :as migrations]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.query.population :as pop]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.jetty :as jetty]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl]
            [swank.swank :as swank]
            [com.puppetlabs.puppetdb.http.server :as server])
  (:use [clojure.java.io :only [file]]
        [clojure.tools.nrepl.transport :only (tty tty-greeting)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.utils :only (cli! configure-logging! inis-to-map with-error-delivery)]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def version (pl-utils/get-version-from-manifest))
(def configuration nil)
(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1&create=false")
(def mq-endpoint "com.puppetlabs.puppetdb.commands")

(defn load-from-mq
  "Process commands from the indicated endpoint on the supplied message queue.

  This function doesn't terminate. If we encounter an exception when
  processing commands from the message queue, we retry the operation
  after reopening a fresh connection with the MQ."
  [mq mq-endpoint discard-dir db]
  (pl-utils/keep-going
   (fn [exception]
     (log/error exception "Error during command processing; reestablishing connection after 10s")
     (Thread/sleep 10000))

   (with-open [conn (mq/connect! mq)]
     (command/process-commands! conn mq-endpoint discard-dir {:db db}))))

(defn db-garbage-collector
  "Compact the indicated database every `interval` minutes.

  This function doesn't terminate. If we encounter an exception during
  compaction, the operation will be retried after `interval` millis."
  [db interval]
  (pl-utils/keep-going
   (fn [exception]
     (log/error exception "Error during DB compaction"))

   (log/info "Beginning database compaction")
   (with-transacted-connection db
     (scf-store/garbage-collect!)
     (log/info "Finished database compaction"))
   (Thread/sleep (* 60 1000 interval))))

(defn configure-commandproc-threads
  "Update the supplied config map with the number of
  command-processing threads to use. If no value exists in the config
  map, default to half the number of CPUs. If only one CPU exists, we
  will use one command-processing thread."
  [config]
  {:pre  [(map? config)]
   :post [(map? %)
          (pos? (get-in % [:command-processing :threads]))]}
  (let [default-nthreads (-> (pl-utils/num-cpus)
                             (/ 2)
                             (int)
                             (max 1))]
    (update-in config [:command-processing :threads] #(or % default-nthreads))))

(defn configure-web-server
  "Update the supplied config map with information about the HTTP webserver to
  start. This will specify client auth, and add a default host/port
  http://puppetdb:8080 if none are supplied (and SSL is not specified)."
  [config]
  {:pre  [(map? config)]
   :post [(map? config)]}
  (assoc-in config [:jetty :need-client-auth] true))

(defn configure-database
  "Update the supplied config map with information about the database. Adds a
  default hsqldb and a gc-interval of 60 minutes. If a single part of the
  database information is specified (such as classname but not subprotocol), no
  defaults will be filled in."
  [{:keys [database global] :as config}]
  {:pre  [(map? config)]
   :post [(map? config)]}
  (let [vardir     (:vardir global)
        default-db {:classname   "org.hsqldb.jdbcDriver"
                    :subprotocol "hsqldb"
                    :subname     (format "file:%s;hsqldb.tx=mvcc;sql.syntax_pgs=true" (file vardir "db"))}]
    (assoc config :database
           (merge {:gc-interval 60} (or database default-db)))))

(defn validate-vardir
  "Checks that `vardir` is specified, exists, and is writeable, throwing
  appropriate exceptions if any condition is unmet."
  [vardir]
  (if-let [vardir (file vardir)]
    (cond
     (not (.isAbsolute vardir))
     (throw (IllegalArgumentException.
             (format "Vardir %s must be an absolute path." vardir)))

     (not (.exists vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s does not exist. Please create it and ensure it is writable." vardir)))

     (not (.isDirectory vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not a directory." vardir)))

     (not (.canWrite vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not writable." vardir))))

    (throw (IllegalArgumentException.
            "Required setting 'vardir' is not specified. Please set it to a writable directory.")))
  vardir)

(defn set-global-configuration!
  "Store away global configuration"
  [config]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (def configuration config)
  config)

(defn parse-config
  "Parses the given config file (if present) and configure its various
  subcomponents."
  [file]
  (let [config (if file (inis-to-map file) {})]
    (-> config
        (configure-logging!)
        (configure-commandproc-threads)
        (configure-web-server)
        (configure-database)
        (set-global-configuration!))))

(defn -main
  [& args]
  (let [[options _]                                (cli! args)
        {:keys [jetty database global] :as config} (parse-config (:config options))
        vardir                                     (validate-vardir (:vardir global))
        db                                         (pl-jdbc/pooled-datasource database)
        db-gc-minutes                              (get database :gc-interval 60)
        mq-dir                                     (str (file vardir "mq"))
        discard-dir                                (file mq-dir "discarded")
        globals                                    {:scf-db     db
                                                    :command-mq {:connection-string mq-addr
                                                                 :endpoint          mq-endpoint}}
        ring-app                                   (server/build-app globals)]

    (when version
      (log/info (format "PuppetDB version %s" version)))

    ;; Ensure the database is migrated to the latest version
    (sql/with-connection db
      (migrate!))

    ;; Initialize database-dependent metrics
    (pop/initialize-metrics db)

    (let [error         (promise)
          broker        (do
                          (log/info "Starting broker")
                          (mq/start-broker! (mq/build-embedded-broker mq-dir)))
          command-procs (let [nthreads (get-in config [:command-processing :threads])]
                          (log/info (format "Starting %d command processor threads" nthreads))
                          (vec (for [n (range nthreads)]
                                 (future (with-error-delivery error
                                           (load-from-mq mq-addr mq-endpoint discard-dir db))))))
          web-app       (do
                          (log/info "Starting query server")
                          (future (with-error-delivery error
                                    (jetty/run-jetty ring-app jetty))))
          db-gc         (do
                          (log/info (format "Starting database compactor (%d minute interval)" db-gc-minutes))
                          (future (with-error-delivery error
                                    (db-garbage-collector db db-gc-minutes))))]

      ;; Start debug REPL if necessary
      (let [{:keys [enabled type host port] :or {type "nrepl" host "localhost"}} (:repl config)]
        (when (= "true" enabled)
          (log/warn (format "Starting %s server on port %d" type port))
          (cond
           (= type "nrepl")
           (nrepl/start-server :port port :transport-fn tty :greeting-fn tty-greeting)

           (= type "swank")
           (swank/start-server :host host :port port))))

      (let [exception (deref error)]
        (doseq [cp command-procs]
          (future-cancel cp))
        (future-cancel web-app)
        (future-cancel db-gc)
        ;; Stop the mq the old-fashioned way
        (mq/stop-broker! broker)

        ;; Now throw the exception so the top-level handler will see it
        (throw exception)))))
