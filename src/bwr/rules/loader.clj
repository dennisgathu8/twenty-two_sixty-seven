(ns bwr.rules.loader
  "Loader and registry for Break-Window Response rule definitions (§7).
   Rules are defined as versioned EDN data (under resources/rules/*.edn)
   and validated against bwr.store.schema/rule before use."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [bwr.store.schema :as schema]
            [bwr.store.query :as store-query]))

(set! *warn-on-reflection* true)

(defn parse-rule
  "Parses and schema-validates a rule map from an EDN string or reader.
   Guarantees :xt/id is set and schema contract is satisfied.
   Throws ex-info on invalid syntax or spec violations."
  [edn-input]
  (let [data (if (string? edn-input)
               (edn/read-string edn-input)
               (edn/read (java.io.PushbackReader. (io/reader edn-input))))]
    (schema/validate-entity! data)))

(defn load-rule-file
  "Loads and validates a rule from a File, Path, or filepath string."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "Rule file not found: " path) {:path path})))
    (parse-rule (slurp f))))

(defn load-rule-resource
  "Loads and validates a rule from a classpath resource path (e.g. 'rules/break_window_substitution_pattern.edn')."
  [resource-path]
  (if-let [res (io/resource resource-path)]
    (parse-rule (slurp res))
    (throw (ex-info (str "Rule resource not found on classpath: " resource-path)
                    {:resource-path resource-path}))))

(defn load-rules-from-dir
  "Loads and validates all *.edn rule files from a directory.
   Returns a map of {rule-id rule-map}."
  [dir-path]
  (let [dir (io/file dir-path)]
    (if (and (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter #(and (.isFile ^java.io.File %) (.endsWith (.getName ^java.io.File %) ".edn")))
           (map (comp parse-rule slurp))
           (map (juxt :rule/id identity))
           (into {}))
      {})))

(defn load-rules-from-resources
  "Loads all built-in rules from the default 'resources/rules' directory.
   Returns a map of {rule-id rule-map}."
  ([]
   (load-rules-from-resources "resources/rules"))
  ([dir-path]
   (load-rules-from-dir dir-path)))

(defn persist-rule!
  "Transacts a validated rule document into the XTDB store node.
   Returns transaction receipt."
  [node rule]
  (let [validated (schema/validate-entity! rule)]
    (store-query/transact! node [validated])))

(defn fetch-rule
  "Retrieves a rule document by ID from an XTDB database snapshot."
  [db rule-id]
  (store-query/entity db rule-id))
