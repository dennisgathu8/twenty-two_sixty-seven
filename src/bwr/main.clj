(ns bwr.main
  "Main application entry point and system lifecycle management for Break-Window Response.
   Provides (start!), (stop!), and (restart!) for REPL-driven development and -main for CLI."
  (:require [org.httpkit.server :as http]
            [bwr.store.node :as store-node]
            [bwr.web.routes :as routes])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private system
  (atom {:server nil
         :node nil
         :started-at nil}))

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
   and records system state. Safe to call repeatedly; will not duplicate running services."
  ([] (start! {:port 3000 :topology :in-memory}))
  ([opts]
   (if (:server @system)
     (do
       (println (str "System is already running on port " (get opts :port 3000) "."))
       :already-running)
     (let [port (get opts :port 3000)
           topology (get opts :topology :in-memory)
           data-dir (get opts :data-dir "data/xtdb")
           node (store-node/start-node! (if (= topology :rocksdb)
                                          {:topology :rocksdb :data-dir data-dir}
                                          {:topology :in-memory}))
           stop-fn (start-server! node port)]
       (reset! system {:server stop-fn
                       :node node
                       :started-at (java.time.Instant/now)})
       (println "Break-Window Response system started successfully.")
       (println (str "HTTP server listening at http://localhost:" port " and https://breakwindow.lan"))
       :started))))

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
  "CLI entry point for running the application standalone."
  [& _args]
  (start! {:port 3000 :topology :rocksdb :data-dir "data/xtdb"})
  (println "Press Ctrl+C to stop.")
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable (fn [] (stop!)))))
