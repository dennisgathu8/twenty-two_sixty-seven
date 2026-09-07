(ns bwr.ingest.spec
  "Boundary specifications for the ingestion pipeline.
   Enforces contract validation so malformed records are rejected before reaching business logic."
  (:require [clojure.spec.alpha :as s]
            [bwr.store.schema :as store-schema]))

(set! *warn-on-reflection* true)

;; Raw FBref record specifications
(s/def :fbref/match-id string?)
(s/def :fbref/minute (s/or :int int? :str string?))
(s/def :fbref/team (s/and string? #(not (clojure.string/blank? %))))
(s/def :fbref/event-type (s/or :str string? :kw keyword?))

(s/def ::raw-fbref-record
  (s/keys :req-un [:fbref/match-id
                   :fbref/minute
                   :fbref/team
                   :fbref/event-type]
          :opt-un [:fbref/player
                   :fbref/player-off
                   :fbref/player-on
                   :fbref/detail
                   :fbref/outcome]))

;; Normalized match event after parsing — strictly matches store event contract
(s/def ::match-event
  :bwr.store.schema/event)

;; Ingestion metadata
(s/def :event/ingested-at inst?)

(s/def ::ingested-event
  (s/merge ::match-event
           (s/keys :req [:event/ingested-at])))

(defn valid-event?
  "Returns true if the parsed record conforms to ::match-event."
  [event]
  (s/valid? ::match-event event))

(defn explain-event
  "Returns clojure.spec explain-data if record is invalid, nil otherwise."
  [event]
  (s/explain-data ::match-event event))
