(ns bwr.test-runner
  "Command-line test runner executing all tests in bwr.*-test namespaces."
  (:require [clojure.test :as test]
            [bwr.main-test]))

(defn -main
  "Runs all test suites and exits with status 0 on success, 1 on failure."
  [& _args]
  (println "Running test suite...")
  (let [results (test/run-tests 'bwr.main-test)]
    (if (or (pos? (:fail results))
            (pos? (:error results)))
      (do
        (println "Tests failed:" results)
        (System/exit 1))
      (do
        (println "All tests passed:" results)
        (System/exit 0)))))
