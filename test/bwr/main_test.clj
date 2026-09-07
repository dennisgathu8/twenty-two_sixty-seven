(ns bwr.main-test
  "Unit tests for system lifecycle management and skeleton HTTP endpoints in bwr.main."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [bwr.main :as main]
            [org.httpkit.client :as http]))

(defn with-clean-system [f]
  (try
    (f)
    (finally
      (main/stop!))))

(use-fixtures :each with-clean-system)

(deftest system-lifecycle-test
  (testing "Initial state is not running"
    (is (false? (:running? (main/system-status)))))

  (testing "Starting system sets running? to true and starts HTTP server"
    (is (= :started (main/start!)))
    (is (true? (:running? (main/system-status))))
    (is (= "System is already running on port 3000."
           (clojure.string/trim (with-out-str (main/start!))))))

  (testing "HTTP endpoints respond while running"
    (let [{:keys [status body]} @(http/get "http://localhost:3000/")
          html (if (string? body) body (slurp body))]
      (is (= 200 status))
      (is (clojure.string/includes? html "Break-Window Response")))
    (let [{:keys [status body]} @(http/get "http://localhost:3000/health")
          edn (if (string? body) body (slurp body))]
      (is (= 200 status))
      (is (clojure.string/includes? edn ":status :ok"))))

  (testing "Stopping system returns :stopped and clears state"
    (is (= :stopped (main/stop!)))
    (is (false? (:running? (main/system-status))))
    (is (= :not-running (main/stop!)))))
