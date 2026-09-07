(ns bwr.main
  "Main application entry point and system lifecycle management for Break-Window Response.
   Provides (start!), (stop!), and (restart!) for REPL-driven development and -main for CLI."
  (:require [org.httpkit.server :as http]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.util.response :as response])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^:private system
  (atom {:server nil
         :started-at nil}))

(defn- default-handler
  "Default root HTTP handler for the skeleton application."
  [request]
  (case (:uri request)
    "/" (-> (response/response
              (str "<!DOCTYPE html>\n"
                   "<html lang=\"en\">\n"
                   "<head><meta charset=\"utf-8\"><title>Break-Window Response</title></head>\n"
                   "<body style=\"font-family: system-ui, sans-serif; max-width: 800px; margin: 2rem auto; padding: 0 1rem;\">\n"
                   "  <h1>Break-Window Response</h1>\n"
                   "  <p>Decision support for FIFA 2026 mandatory hydration breaks (22' & 67').</p>\n"
                   "  <p>Status: <strong>Online</strong> (Parens to Production / Clojure / XTDB / core.logic)</p>\n"
                   "</body>\n"
                   "</html>"))
            (response/content-type "text/html; charset=utf-8"))
    "/health" (-> (response/response "{:status :ok :service :break-window-response}")
                  (response/content-type "application/edn"))
    (response/not-found "Not Found")))

(defn- app-handler
  "Ring application handler wrapped with basic defaults."
  []
  (wrap-defaults default-handler site-defaults))

(defn start-server!
  "Starts the http-kit web server on the given port (default: 3000).
   Returns the stop-server function."
  ([] (start-server! 3000))
  ([port]
   (println (str "Starting Break-Window Response server on port " port "..."))
   (http/run-server (app-handler) {:port port})))

(defn start!
  "Starts the system lifecycle: starts web server on port 3000 and records state.
   Safe to call repeatedly; will not start a duplicate server if one is already running."
  []
  (if (:server @system)
    (println "System is already running on port 3000.")
    (let [stop-fn (start-server! 3000)]
      (reset! system {:server stop-fn
                      :started-at (java.time.Instant/now)})
      (println "Break-Window Response system started successfully.")
      (println "HTTP server listening at http://localhost:3000 and https://breakwindow.lan")
      :started)))

(defn stop!
  "Stops the running system and web server gracefully."
  []
  (if-let [stop-fn (:server @system)]
    (do
      (println "Stopping Break-Window Response server...")
      (stop-fn :timeout 100)
      (reset! system {:server nil :started-at nil})
      (println "Break-Window Response system stopped.")
      :stopped)
    (do
      (println "System is not running.")
      :not-running)))

(defn restart!
  "Restarts the system lifecycle by stopping and then starting."
  []
  (stop!)
  (start!))

(defn system-status
  "Returns current system status map."
  []
  {:running? (some? (:server @system))
   :started-at (:started-at @system)})

(defn -main
  "CLI entry point for running the application standalone."
  [& _args]
  (start!)
  (println "Press Ctrl+C to stop.")
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable (fn [] (stop!)))))
