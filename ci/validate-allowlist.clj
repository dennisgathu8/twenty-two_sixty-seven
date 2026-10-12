(ns ci.validate-allowlist
  "Validates .github/cve-allowlist.edn syntax, required fields, and expiry dates (§9.5, §11, ADR-004)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.time LocalDate]))

(defn -main [& args]
  (let [path (or (first args) ".github/cve-allowlist.edn")
        f (java.io.File. path)]
    (if-not (.exists f)
      (do
        (println "ERROR: Allowlist file not found at" path)
        (System/exit 1))
      (let [entries (try
                      (edn/read-string (slurp f))
                      (catch Exception e
                        (println "ERROR: Failed to parse allowlist EDN:" (.getMessage e))
                        (System/exit 1)))]
        (if-not (vector? entries)
          (do
            (println "ERROR: Allowlist must be a vector of maps.")
            (System/exit 1))
          (let [today (LocalDate/now)
                errors (atom [])]
            (doseq [[idx entry] (map-indexed vector entries)]
              (let [{:keys [id ecosystem package justification expires-on]} entry]
                (when (or (nil? id) (str/blank? (str id)))
                  (swap! errors conj (str "Entry " idx ": missing :id")))
                (when-not (contains? #{:maven :npm} ecosystem)
                  (swap! errors conj (str "Entry " idx " (" id "): :ecosystem must be :maven or :npm, got: " ecosystem)))
                (when (or (nil? package) (str/blank? (str package)))
                  (swap! errors conj (str "Entry " idx " (" id "): missing :package")))
                (when (or (nil? justification) (str/blank? (str justification)))
                  (swap! errors conj (str "Entry " idx " (" id "): missing mandatory non-blank :justification")))
                (if (or (nil? expires-on) (str/blank? (str expires-on)))
                  (swap! errors conj (str "Entry " idx " (" id "): missing mandatory :expires-on date"))
                  (try
                    (let [exp-date (LocalDate/parse (str expires-on))]
                      (when (.isBefore exp-date today)
                        (swap! errors conj (str "Entry " idx " (" id "): EXPIRED on " expires-on " (today is " today "). Exceptions cannot silently persist!"))))
                    (catch Exception _
                      (swap! errors conj (str "Entry " idx " (" id "): invalid :expires-on format; must be YYYY-MM-DD, got: " expires-on)))))))
            (if (seq @errors)
              (do
                (println "ERROR: Allowlist validation failed:")
                (doseq [err @errors]
                  (println " -" err))
                (System/exit 1))
              (do
                (println (str "Allowlist validation passed: " (count entries) " valid active exception(s)."))
                (System/exit 0)))))))))
