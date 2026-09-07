(ns bwr.test-runner
  "Directory-scoped test runner supporting -d / --dir flags, compatible with
   the verification patterns used throughout the build sequence."
  (:require [clojure.test :as test]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- parse-args
  "Parses command-line arguments into a list of test directories.
   Accepts repeated -d or --dir arguments, e.g. -d test/bwr/store -d test/bwr/ingest.
   Defaults to [\"test\"] if none provided."
  [args]
  (loop [remaining (seq args)
         dirs []]
    (if-not remaining
      (if (seq dirs) dirs ["test"])
      (let [[opt val & rest-args] remaining]
        (cond
          (contains? #{"-d" "--dir"} opt)
          (recur rest-args (conj dirs val))

          :else
          (recur (cons val rest-args) dirs))))))

(defn- file->ns
  "Derives a Clojure namespace symbol from a test file path relative to a test root.
   E.g., test/bwr/store/store_test.clj -> bwr.store.store-test"
  [^java.io.File file]
  (let [path (.getPath file)
        ;; Normalize relative path after test/
        rel-path (if (str/starts-with? path "test/")
                   (subs path 5)
                   path)
        without-ext (str/replace rel-path #"\.clj$" "")
        ns-str (-> without-ext
                   (str/replace "/" ".")
                   (str/replace "_" "-"))]
    (symbol ns-str)))

(defn- find-test-namespaces
  "Discovers all test namespaces within the given directories."
  [dirs]
  (->> dirs
       (map io/file)
       (filter #(.exists ^java.io.File %))
       (mapcat file-seq)
       (filter (fn [^java.io.File f]
                 (and (.isFile f)
                      (str/ends-with? (.getName f) "_test.clj"))))
       (map file->ns)
       distinct
       vec))

(defn -main
  "Runs test suites in specified directories (-d <dir>) or default test directory."
  [& args]
  (let [dirs (parse-args args)
        test-namespaces (find-test-namespaces dirs)]
    (println (str "Running tests in directories: " (pr-str dirs)))
    (if (empty? test-namespaces)
      (do
        (println "No test namespaces found in specified directories.")
        (System/exit 0))
      (do
        (println (str "Discovered " (count test-namespaces) " test namespace(s): "
                      (str/join ", " (map name test-namespaces))))
        (doseq [ns-sym test-namespaces]
          (require ns-sym :reload))
        (let [results (apply test/run-tests test-namespaces)
              failed? (or (pos? (:fail results 0))
                          (pos? (:error results 0)))]
          (if failed?
            (do
              (println "Tests FAILED:" results)
              (System/exit 1))
            (do
              (println "All tests PASSED:" results)
              (System/exit 0))))))))
