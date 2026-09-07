(ns bwr.store.schema
  "Schema specifications and entity validation contracts for Break-Window Response entities.
   Covers tournament, match, stoppage, event, rule, and recommendation documents."
  (:require [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Common & Enumerated Types
;; ============================================================================

(s/def :match/data-source #{:source/fbref :source/wikipedia :source/manual})
(s/def :match/data-quality #{:quality/verified :quality/unverified :quality/disputed})

(s/def :stoppage/type #{:stoppage.type/hydration :stoppage.type/injury :stoppage.type/var})
(s/def :stoppage/half #{1 2})

(s/def :event/type #{:event.type/goal
                     :event.type/card
                     :event.type/substitution
                     :event.type/shot})

;; ============================================================================
;; Entity Specs
;; ============================================================================

;; 1. Tournament
(s/def :tournament/id string?)
(s/def :tournament/name string?)
(s/def :tournament/start-date inst?)
(s/def :tournament/end-date inst?)

(s/def ::tournament
  (s/keys :req [:tournament/id
                :tournament/name
                :tournament/start-date
                :tournament/end-date]
          :opt [:xt/id]))

;; 2. Match
(s/def :match/id string?)
(s/def :match/tournament string?)
(s/def :match/home-team string?)
(s/def :match/away-team string?)
(s/def :match/kickoff inst?)
(s/def :match/venue string?)

(s/def ::match
  (s/keys :req [:match/id
                :match/tournament
                :match/home-team
                :match/away-team
                :match/kickoff
                :match/venue
                :match/data-source
                :match/data-quality]
          :opt [:xt/id]))

;; 3. Stoppage
(s/def :stoppage/id string?)
(s/def :stoppage/match string?)
(s/def :stoppage/clock-minute pos-int?)
(s/def :stoppage/duration-s pos-int?)

(s/def ::stoppage
  (s/keys :req [:stoppage/id
                :stoppage/match
                :stoppage/type
                :stoppage/half
                :stoppage/clock-minute
                :stoppage/duration-s]
          :opt [:xt/id]))

;; 4. Match Event
(s/def :event/id string?)
(s/def :event/match string?)
(s/def :event/minute (s/and int? #(>= % 0)))
(s/def :event/team string?)
(s/def :event/detail map?)
(s/def :event/source :match/data-source)

(s/def ::event
  (s/keys :req [:event/id
                :event/match
                :event/type
                :event/minute
                :event/team
                :event/detail
                :event/source]
          :opt [:xt/id]))

;; 5. Rule
(s/def :rule/id keyword?)
(s/def :rule/version string?)
(s/def :rule/description string?)
(s/def :rule/window-s pos-int?)
(s/def :rule/predicate list?)

(s/def ::rule
  (s/keys :req [:rule/id
                :rule/version
                :rule/description
                :rule/window-s
                :rule/predicate]
          :opt [:xt/id]))

;; 6. Generated Recommendation
(s/def :recommendation/id string?)
(s/def :recommendation/match string?)
(s/def :recommendation/stoppage string?)
(s/def :recommendation/rule keyword?)
(s/def :recommendation/rule-version string?)
(s/def :recommendation/team string?)
(s/def :recommendation/text string?)
(s/def :recommendation/evidence (s/coll-of string? :kind vector? :min-count 1))
(s/def :recommendation/generated-at inst?)
(s/def :recommendation/tx (s/or :int int? :inst inst?))

(s/def ::recommendation
  (s/keys :req [:recommendation/id
                :recommendation/match
                :recommendation/stoppage
                :recommendation/rule
                :recommendation/rule-version
                :recommendation/team
                :recommendation/text
                :recommendation/evidence
                :recommendation/generated-at
                :recommendation/tx]
          :opt [:xt/id]))

;; ============================================================================
;; Helper Utilities
;; ============================================================================

(defn entity-type
  "Identifies the entity type keyword from doc attributes."
  [doc]
  (cond
    (:tournament/id doc)     ::tournament
    (:match/id doc)          ::match
    (:stoppage/id doc)       ::stoppage
    (:event/id doc)          ::event
    (:rule/id doc)           ::rule
    (:recommendation/id doc) ::recommendation
    :else nil))

(defn entity-id
  "Extracts the primary entity identifier value from a document map."
  [doc]
  (or (:xt/id doc)
      (:tournament/id doc)
      (:match/id doc)
      (:stoppage/id doc)
      (:event/id doc)
      (:rule/id doc)
      (:recommendation/id doc)))

(defn ensure-xt-id
  "Ensures document map has an :xt/id key matching its entity id."
  [doc]
  (if (:xt/id doc)
    doc
    (if-let [id (entity-id doc)]
      (assoc doc :xt/id id)
      (throw (ex-info "Cannot determine :xt/id for document without primary ID" {:doc doc})))))

(defn valid-entity?
  "Returns true if doc is a valid document according to its entity spec."
  [doc]
  (if-let [t (entity-type doc)]
    (s/valid? t (ensure-xt-id doc))
    false))

(defn validate-entity!
  "Validates a document against its schema spec.
   Returns the doc with :xt/id guaranteed, or throws ex-info with explain data."
  [doc]
  (let [doc-with-id (ensure-xt-id doc)
        t (entity-type doc-with-id)]
    (if-not t
      (throw (ex-info "Unrecognized entity type for document"
                      {:doc doc-with-id}))
      (if (s/valid? t doc-with-id)
        doc-with-id
        (throw (ex-info (str "Document failed schema validation for " t)
                        {:doc doc-with-id
                         :explain (s/explain-data t doc-with-id)}))))))
