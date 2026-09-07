(ns user
  "Development user namespace. Automatically loaded when starting a REPL with the :dev alias.
   Exposes system lifecycle controls (start!), (stop!), and (restart!)."
  (:require [bwr.main :as main]))

(defn start!
  "Starts the Break-Window Response system."
  []
  (main/start!))

(defn stop!
  "Stops the Break-Window Response system."
  []
  (main/stop!))

(defn restart!
  "Restarts the Break-Window Response system."
  []
  (main/restart!))

(println "BWR REPL loaded. Type (start!) to start server on :3000.")
