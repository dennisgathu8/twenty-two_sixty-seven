(ns bwr.main
  "Main application entry point and system lifecycle management for Break-Window Response.
   Provides (start!), (stop!), and (restart!) for REPL-driven development and -main for CLI."
  (:require [org.httpkit.server :as http]
            [bwr.store.node :as store-node]
            [bwr.store.seed :as seed]
            [bwr.auth.session :as session]
            [bwr.web.routes :as routes])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private system
  (atom {:server nil
         :node nil
         :started-at nil}))

(defn- emit-test-environment-warning!
  "Emits a loud, unmissable multi-line banner if BWR_ENV=test is active."
  []
  (when (session/test-environment?)
    (binding [*out* *err*]
      (println "*******************************************************************************")
      (println "* WARNING: BWR_ENV=test IS CURRENTLY ACTIVE!                                  *")
      (println "* --------------------------------------------------------------------------- *")
      (println "* - TEST-ONLY MAGIC-LINK MINTING ROUTE /test/auth/magic-link IS MOUNTED       *")
      (println "* - SESSION COOKIE Secure FLAG IS RELAXED FOR LOCAL HTTP TESTING              *")
      (println "* - THIS RUNTIME MODE MUST NEVER RUN IN STAGING OR PRODUCTION (§9, §10)       *")
      (println "*******************************************************************************"))))

(defn start-server!
  "Starts the http-kit web server on the given port (default: 3000) using the given XTDB node.
   Returns the stop-server function."
  ([node] (start-server! node 3000))
  ([node port]
   (println (str "Starting Break-Window Response server on port " port "..."))
   (let [app (routes/create-app node)]
     (http/run-server app {:port port}))))

(defn start!
  "Starts the system lifecycle: starts XTDB node, starts web server on port 3000,
   and records system state. Safe to call repeatedly; will not duplicate running services.
   Options map:
     :port      - port to listen on (default: 3000)
     :topology  - :in-memory or :rocksdb (default: :in-memory)
     :data-dir  - RocksDB data directory (default: 'data/xtdb')
     :seed?     - boolean, whether to seed test fixtures (default: false)"
  ([] (start! {:port 3000 :topology :in-memory}))
  ([opts]
   (if (:server @system)
     (do
       (println (str "System is already running on port " (get opts :port 3000) "."))
       :already-running)
     (do
       (emit-test-environment-warning!)
       (let [port (get opts :port 3000)
             topology (get opts :topology :in-memory)
             data-dir (get opts :data-dir "data/xtdb")
             seed? (get opts :seed? false)
             node (store-node/start-node! (if (= topology :rocksdb)
                                            {:topology :rocksdb :data-dir data-dir}
                                            {:topology :in-memory}))]
         (when seed?
           (println "Seeding verified match fixtures (M42 France vs Iraq) into XTDB...")
           (let [res (seed/seed-fixtures! node)]
             (println (str "Seeded " (:matches res) " match, " (:stoppages res) " stoppages, "
                           (:events res) " events, " (:rules res) " rules, "
                           (:recommendations res) " recommendations."))))
         (let [stop-fn (start-server! node port)]
           (reset! system {:server stop-fn
                           :node node
                           :started-at (java.time.Instant/now)})
           (println "Break-Window Response system started successfully.")
           (println (str "HTTP server listening at http://localhost:" port " and https://breakwindow.lan"))
           :started))))))

(defn stop!
  "Stops the running system, web server, and XTDB node gracefully."
  []
  (if-let [stop-fn (:server @system)]
    (do
      (println "Stopping Break-Window Response server...")
      (stop-fn :timeout 100)
      (when-let [node (:node @system)]
        (store-node/stop-node! node))
      (reset! system {:server nil :node nil :started-at nil})
      (println "Break-Window Response system stopped.")
      :stopped)
    (do
      (println "System is not running.")
      :not-running)))

(defn restart!
  "Restarts the system lifecycle by stopping and then starting."
  ([] (restart! {:port 3000 :topology :in-memory}))
  ([opts]
   (stop!)
   (start! opts)))

(defn system-status
  "Returns current system status map."
  []
  {:running? (some? (:server @system))
   :started-at (:started-at @system)})

(defn -main
  "CLI entry point for running the application standalone.
   Accepts:
     --seed      - seeds M42 verified fixtures on startup (uses :in-memory unless --rocksdb specified)
     --in-memory - explicitly use in-memory XTDB topology
     --rocksdb   - use RocksDB persistence (default for standalone -main)
     --port N    - listen on port N (default: 3000)"
  [& args]
  (let [arg-set (set args)
        seed? (boolean (contains? arg-set "--seed"))
        in-mem? (boolean (or (contains? arg-set "--in-memory")
                             (and seed? (not (contains? arg-set "--rocksdb")))))
        port (let [idx (.indexOf ^java.util.List (vec args) "--port")]
               (if (and (>= idx 0) (< (inc idx) (count args)))
                 (Integer/parseInt (nth args (inc idx)))
                 3000))
        opts {:port port
              :topology (if in-mem? :in-memory :rocksdb)
              :data-dir "data/xtdb"
              :seed? seed?}]
    (start! opts)
    (println "Press Ctrl+C to stop.")
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (stop!))))
    @(promise)))
