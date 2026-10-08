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
(s/def ::ip-literal (s/and string? #(sec/valid-ip-literal? %)))
(s/def ::trusted-proxies (s/coll-of ::ip-literal :kind set?))
(s/def ::bind-address ::ip-literal)

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

(defn parse-trusted-proxies
  "Parses and spec-validates a comma-separated list of IP literals for BWR_TRUSTED_PROXIES.
   Returns a set of normalized IP literal strings. Default is empty set (trust nothing).
   Throws ex-info if any entry is not a valid IP literal."
  [raw-val]
  (cond
    (nil? raw-val)
    #{}

    (and (string? raw-val) (str/blank? raw-val))
    #{}

    (string? raw-val)
    (let [tokens (map str/trim (str/split raw-val #","))]
      (reduce
       (fn [acc token]
         (if (str/blank? token)
           acc
           (if (s/valid? ::ip-literal token)
             (conj acc (sec/normalize-ip token))
             (throw (ex-info (str "Invalid configuration for BWR_TRUSTED_PROXIES: '" token "' is not a valid IP literal")
                             {:var "BWR_TRUSTED_PROXIES"
                              :invalid-entry token
                              :raw-value raw-val})))))
       #{}
       tokens))

    (set? raw-val)
    (do
      (doseq [entry raw-val]
        (when-not (s/valid? ::ip-literal entry)
          (throw (ex-info (str "Invalid configuration for BWR_TRUSTED_PROXIES: '" entry "' is not a valid IP literal")
                          {:var "BWR_TRUSTED_PROXIES"
                           :invalid-entry entry}))))
      (into #{} (map sec/normalize-ip raw-val)))

    :else
    (throw (ex-info "Invalid configuration for BWR_TRUSTED_PROXIES: expected comma-separated string or set of IP literals"
                    {:var "BWR_TRUSTED_PROXIES" :raw-value raw-val}))))

(defn resolve-trusted-proxies
  "Resolves and spec-validates trusted proxies from environment or explicit map.
   Recognizes BWR_TRUSTED_PROXIES (default empty, trust nothing).
   Throws ex-info if any entry fails IP literal validation."
  ([] (resolve-trusted-proxies (System/getenv)))
  ([env]
   (parse-trusted-proxies (get env "BWR_TRUSTED_PROXIES"))))

(defn- log-trusted-proxies-config!
  "Logs effective trusted proxies configuration at startup."
  [trusted-proxies]
  (if (empty? trusted-proxies)
    (println "Trusted proxies: none (trust nothing, direct client attribution active).")
    (println (str "Trusted proxies: " (str/join ", " (sort trusted-proxies)) "."))))

(defn parse-bind-address
  "Parses and spec-validates the BWR_BIND address as an IP literal.
   Defaults to '127.0.0.1'. Throws ex-info if value is not a valid IP literal."
  [raw-val]
  (cond
    (nil? raw-val) "127.0.0.1"
    (and (string? raw-val) (str/blank? raw-val)) "127.0.0.1"
    (string? raw-val)
    (let [trimmed (str/trim raw-val)]
      (if (s/valid? ::bind-address trimmed)
        (sec/normalize-ip trimmed)
        (throw (ex-info (str "Invalid configuration for BWR_BIND: '" trimmed "' is not a valid IP literal")
                        {:var "BWR_BIND"
                         :invalid-entry trimmed
                         :raw-value raw-val}))))
    :else
    (throw (ex-info (str "Invalid configuration for BWR_BIND: expected IP literal string, got " raw-val)
                    {:var "BWR_BIND" :raw-value raw-val}))))

(defn resolve-bind-address
  "Resolves and spec-validates the bind address from environment or explicit map.
   Recognizes BWR_BIND (default '127.0.0.1').
   Throws ex-info if value fails IP literal validation."
  ([] (resolve-bind-address (System/getenv)))
  ([env]
   (parse-bind-address (get env "BWR_BIND"))))

(defn- log-bind-address-config!
  "Logs effective bind address at startup.
   Emits a loud warning banner if the configured bind address is non-loopback."
  [bind-ip]
  (if-not (sec/loopback-ip? bind-ip)
    (binding [*out* *err*]
      (println "*******************************************************************************")
      (println "* ATTENTION: NON-LOOPBACK BIND ADDRESS CONFIGURED AT STARTUP!                 *")
      (println "* --------------------------------------------------------------------------- *")
      (let [msg (str "* - SERVER BOUND TO NON-LOOPBACK INTERFACE: " bind-ip)
            pad (apply str (repeat (max 1 (- 78 (count msg))) " "))]
        (println (str msg pad "*")))
      (println "* - PORT MAY BE ACCESSIBLE FROM EXTERNAL NETWORK CLIENTS                      *")
      (println "* - ENSURE REVERSE PROXY / FIREWALL GUARDS THIS INTERFACE (§9.4)              *")
      (println "*******************************************************************************")
      (println (str "Server bound to IP: " bind-ip " (NON-LOOPBACK WARNING).")))
    (println (str "Server bound to loopback IP: " bind-ip "."))))

(defn- validate-bind-address-safety!
  "Ensures that if BWR_ENV=test is active, the bind address must be loopback.
   Refuses startup with ex-info if test mode is configured with a non-loopback bind."
  [bind-ip]
  (when (and (session/test-environment?)
             (not (sec/loopback-ip? bind-ip)))
    (throw (ex-info (str "Refusing to start: BWR_ENV=test cannot be bound to non-loopback address '" bind-ip
                         "'. Test-only routes must never be exposed to a network (§9, §10).")
                    {:bind bind-ip :bwr-env "test"}))))

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

(defn relaxed-rate-limit?
  "Returns true if the effective rate exceeds 100 requests per 60 seconds (100 req/min).
   Calculated as: (max-requests * 60) / window-seconds > 100.
   Always returns a boolean."
  [{:keys [max-requests window-seconds]}]
  (if (and (number? max-requests) (number? window-seconds) (pos? window-seconds))
    (let [effective-rate (/ (* (double max-requests) 60.0) (double window-seconds))]
      (> effective-rate 100.0))
    false))

(defn- log-rate-limiter-config!
  "Logs effective rate limiter configuration at startup.
   Emits a loud warning banner if the effective rate exceeds 100 requests per 60 seconds."
  [{:keys [max-requests window-seconds] :as cfg}]
  (if (relaxed-rate-limit? cfg)
    (binding [*out* *err*]
      (println "*******************************************************************************")
      (println "* ATTENTION: RELAXED RATE LIMIT CONFIGURED AT STARTUP!                        *")
      (println "* --------------------------------------------------------------------------- *")
      (let [rate (Math/round (/ (* (double max-requests) 60.0) (double window-seconds)))
            msg (str "* - EFFECTIVE LIMIT: " max-requests " REQS / " window-seconds "S (~" rate " REQS/MIN)")
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
   (let [bind-ip (get opts :bind-address (get opts :bind "127.0.0.1"))
         app (routes/create-app node opts)]
     (println (str "Starting Break-Window Response server on " bind-ip ":" port "..."))
     (http/run-server app {:ip bind-ip :port port}))))

(defn start!
  "Starts the system lifecycle: starts XTDB node, starts web server,
   and records system state. Safe to call repeatedly; will not duplicate running services.
   Options map:
     :port             - port to listen on (default: 3000)
     :bind             - bind IP literal (default: '127.0.0.1' or BWR_BIND)
     :trusted-proxies  - set of trusted proxy IP literals (default: #{} or BWR_TRUSTED_PROXIES)
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
       (let [bind-ip (or (:bind opts) (resolve-bind-address))
             _ (validate-bind-address-safety! bind-ip)
             _ (log-bind-address-config! bind-ip)
             trusted-proxies (or (:trusted-proxies opts) (resolve-trusted-proxies))
             _ (log-trusted-proxies-config! trusted-proxies)
             rate-cfg (or (:rate-limit-opts opts) (resolve-rate-limit-config))
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
         (let [server-opts (assoc opts
                                  :rate-limiter rate-limiter
                                  :bind-address bind-ip
                                  :trusted-proxies trusted-proxies)
               stop-fn (start-server! node port server-opts)]
           (reset! system {:server stop-fn
                           :node node
                           :rate-limiter rate-limiter
                           :bind-address bind-ip
                           :trusted-proxies trusted-proxies
                           :started-at (java.time.Instant/now)})
           (println "Break-Window Response system started successfully.")
           (println (str "HTTP server listening at http://" bind-ip ":" port " and https://breakwindow.lan"))
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
     --port N    - listen on port N (default: 3000)
     --bind IP   - bind to IP literal address (default: 127.0.0.1 or BWR_BIND)"
  [& args]
  (let [arg-set (set args)
        seed? (boolean (contains? arg-set "--seed"))
        in-mem? (boolean (or (contains? arg-set "--in-memory")
                             (and seed? (not (contains? arg-set "--rocksdb")))))
        port (let [idx (.indexOf ^java.util.List (vec args) "--port")]
               (if (and (>= idx 0) (< (inc idx) (count args)))
                 (Integer/parseInt (nth args (inc idx)))
                 3000))
        bind (let [idx (.indexOf ^java.util.List (vec args) "--bind")]
               (if (and (>= idx 0) (< (inc idx) (count args)))
                 (nth args (inc idx))
                 nil))
        opts {:port port
              :bind (or bind (resolve-bind-address))
              :topology (if in-mem? :in-memory :rocksdb)
              :data-dir "data/xtdb"
              :seed? seed?}]
    (start! opts)
    (println "Press Ctrl+C to stop.")
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (stop!))))
    @(promise)))
