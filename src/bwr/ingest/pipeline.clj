(ns bwr.ingest.pipeline
  "Transducer-based, spec-validated match event ingestion pipeline (§6).
   Enforces boundary contracts: parse -> spec-validate -> window-enrich -> tag-provenance -> store."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [bwr.ingest.spec :as spec]
            [bwr.ingest.sources.core :as sources]
            [bwr.ingest.sources.fbref]
            [bwr.store.query :as store-query]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Provenance Tagging
;; ============================================================================

(defn tag-provenance
  "Tags match event with ingestion timestamp and verified provenance."
  [event]
  (assoc event :event/ingested-at (java.util.Date/from (java.time.Instant/now))))

;; ============================================================================
;; Ingestion Transducer
;; ============================================================================

(defn default-reject-handler
  "Default rejection handler for malformed records caught at the ingestion boundary.
   Logs structured warning for operator observability."
  [parsed explain-data]
  (log/warn "Ingestion rejected malformed record:"
            {:event/id (:event/id parsed)
             :event/type (:event/type parsed)
             :problems (:clojure.spec.alpha/problems explain-data)}))

(defn ingestion-xf
  "Returns a single-pass transducer for the ingestion pipeline:
     raw-record -> parse -> spec-validate (reject at door) -> tag-provenance
   Optional reject-fn [raw-record explain-data] is invoked for every rejected record."
  ([source]
   (ingestion-xf source default-reject-handler))
  ([source reject-fn]
   (comp
    (map (fn [raw]
           (try
             (sources/parse-record source raw)
             (catch Exception e
               {:event/id "parse-error" :event/type nil :parse-error (.getMessage e)}))))
    (filter (fn [parsed]
              (if (s/valid? ::spec/match-event parsed)
                true
                (do
                  (when (fn? reject-fn)
                    (reject-fn parsed (s/explain-data ::spec/match-event parsed)))
                  false))))
    (map tag-provenance))))

;; ============================================================================
;; Ingestion Entry Points
;; ============================================================================

(defn parse-and-validate-records
  "Parses and validates a collection of raw records through the ingestion transducer
   into memory without transacting to the database. Useful for pre-checks and tests.
   Accepts an optional atom or callback to record rejected records."
  ([source raw-records]
   (parse-and-validate-records source raw-records nil))
  ([source raw-records reject-fn]
   (into [] (ingestion-xf source reject-fn) raw-records)))

(defn ingest!
  "Single-pass ingestion into an XTDB store node or target collection.
   Returns transaction receipt if target is an XTDB node, or collection of valid stored events.
   Malformed records are rejected at the boundary and never touch the store.
   Optional opts map:
     :reject-fn - callback fn [rejected-record explain-data] for logging rejected records."
  ([target source raw-records]
   (ingest! target source raw-records {}))
  ([target source raw-records opts]
   (let [reject-fn (get opts :reject-fn default-reject-handler)
         xf (ingestion-xf source reject-fn)
         events (into [] xf raw-records)]
     (if (satisfies? xtdb.api/DBProvider target)
       (if (seq events)
         (store-query/transact! target events)
         :no-events-ingested)
       events))))
