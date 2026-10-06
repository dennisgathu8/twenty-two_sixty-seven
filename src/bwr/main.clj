(ns bwr.main
  "Main application entry point and system lifecycle management for Break-Window Response.
   Provides (start!), (stop!), and (restart!) for REPL-driven development and -main for CLI."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [bwr.store.node :as store-node]
            [bwr.store.seed :as seed]
            [bwr.auth.session :as session]
            [bwr.security.middleware :as sec]
            [bwr.web.routes :as routes])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private system
  (atom {:server nil
         :node nil
         :rate-limiter nil
         :started-at nil}))

(s/def ::rate-limit-max pos-int?)
(s/def ::rate-limit-window-s pos-int?)

(defn- parse-positive-int
  "Parses and validates a positive integer configuration value with clojure.spec."
  [raw-val var-name default-val spec-kw]
  (cond
    (nil? raw-val) default-val
    (and (string? raw-val) (str/blank? raw-val)) default-val
    (integer? raw-val)
    (if (s/valid? spec-kw raw-val)
      raw-val
      (throw (ex-info (str "Invalid configuration for " var-name ": value must be a positive integer, got " raw-val)
                      {:var var-name :value raw-val :spec spec-kw})))
    (string? raw-val)
    (let [parsed (try (Long/parseLong (str/trim raw-val))
                      (catch Exception _
                        (throw (ex-info (str "Invalid configuration for " var-name ": expected positive integer, got '" raw-val "'")
                                        {:var var-name :value raw-val}))))]
      (if (s/valid? spec-kw parsed)
        parsed
        (throw (ex-info (str "Invalid configuration for " var-name ": value must be a positive integer, got " parsed)
                        {:var var-name :value parsed :spec spec-kw}))))
    :else
    (throw (ex-info (str "Invalid configuration for " var-name ": expected positive integer, got " raw-val)
                    {:var var-name :value raw-val}))))

(defn resolve-rate-limit-config
  "Resolves and spec-validates rate limiter configuration from environment or explicit map.
   Recognizes BWR_RATE_LIMIT_MAX (default 100) and BWR_RATE_LIMIT_WINDOW_S (default 60).
   Throws ex-info if values fail clojure.spec validation."
  ([] (resolve-rate-limit-config (System/getenv)))
  ([env]
   (let [max-reqs (parse-positive-int (get env "BWR_RATE_LIMIT_MAX") "BWR_RATE_LIMIT_MAX" 100 ::rate-limit-max)
         window-s (parse-positive-int (get env "BWR_RATE_LIMIT_WINDOW_S") "BWR_RATE_LIMIT_WINDOW_S" 60 ::rate-limit-window-s)]
     {:max-requests max-reqs
      :window-seconds window-s})))

(defn- log-rate-limiter-config!
  "Logs effective rate limiter configuration at startup.
   Emits a loud warning banner if the configured maximum exceeds the default (100)."
  [{:keys [max-requests window-seconds]}]
  (if (> (long max-requests) 100)
    (binding [*out* *err*]
      (println "*******************************************************************************")
      (println "* ATTENTION: RELAXED RATE LIMIT CONFIGURED AT STARTUP!                        *")
      (println "* --------------------------------------------------------------------------- *")
      (let [msg (str "* - EFFECTIVE LIMIT: " max-requests " REQUESTS PER " window-seconds " SECONDS")
            pad (apply str (repeat (max 1 (- 78 (count msg))) " "))]
        (println (str msg pad "*")))
      (println "* - THIS RELAXED THRESHOLD MUST NEVER BE ACTIVE IN PRODUCTION (§9.4, §10)     *")
      (println "*******************************************************************************")
      (println (str "Rate limiter active: " max-requests " requests per " window-seconds "s (RELAXED).")))
    (println (str "Rate limiter active: " max-requests " requests per " window-seconds "s."))))

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
   Accepts optional opts map passed down to routes/create-app. Returns the stop-server function."
  ([node] (start-server! node 3000 {}))
  ([node port] (start-server! node port {}))
  ([node port opts]
   (println (str "Starting Break-Window Response server on port " port "..."))
   (let [app (routes/create-app node opts)]
     (http/run-server app {:port port}))))

(defn start!
  "Starts the system lifecycle: starts XTDB node, starts web server on port 3000,
   and records system state. Safe to call repeatedly; will not duplicate running services.
   Options map:
     :port             - port to listen on (default: 3000)
     :topology         - :in-memory or :rocksdb (default: :in-memory)
     :data-dir         - RocksDB data directory (default: 'data/xtdb')
     :seed?            - boolean, whether to seed test fixtures (default: false)
     :rate-limiter     - optional pre-configured rate limiter instance
     :rate-limit-opts  - optional map with {:max-requests N :window-seconds S}"
  ([] (start! {:port 3000 :topology :in-memory}))
  ([opts]
   (if (:server @system)
     (do
       (println (str "System is already running on port " (get opts :port 3000) "."))
       :already-running)
     (do
       (emit-test-environment-warning!)
       (let [rate-cfg (or (:rate-limit-opts opts) (resolve-rate-limit-config))
             _ (log-rate-limiter-config! rate-cfg)
             rate-limiter (or (:rate-limiter opts)
                              (sec/create-rate-limiter rate-cfg))
             port (get opts :port 3000)
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
         (let [stop-fn (start-server! node port (assoc opts :rate-limiter rate-limiter))]
           (reset! system {:server stop-fn
                           :node node
                           :rate-limiter rate-limiter
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
      (reset! system {:server nil :node nil :rate-limiter nil :started-at nil})
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
