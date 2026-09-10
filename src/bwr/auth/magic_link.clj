(ns bwr.auth.magic-link
  "Passwordless, HMAC-signed single-use magic link authentication (§3, §9.2).
   Provides cryptographically signed token generation, email dispatch via Mailpit,
   and race-free atomic check-and-invalidate against XTDB storage."
  (:require [clojure.tools.logging :as log]
            [buddy.sign.jwt :as jwt]
            [xtdb.api :as xt]
            [bwr.store.schema :as schema]
            [bwr.store.query :as store-query]
            [bwr.auth.email :as email]
            [bwr.security.middleware :as security]))

(set! *warn-on-reflection* true)

(def default-secret
  "Fallback secret for development and testing. Production injects via environment."
  "bwr-development-secret-key-do-not-use-in-production-1234567890")

(defn get-auth-secret
  "Retrieves the HMAC signing secret from system environment or fallback."
  []
  (or (System/getenv "BWR_AUTH_SECRET")
      default-secret))

;; Default rate limiters for magic link generation (§9.4, §9.7)
(defonce default-ip-limiter
  (security/create-rate-limiter {:max-requests 5 :window-seconds 60}))

(defonce default-identity-limiter
  (security/create-rate-limiter {:max-requests 3 :window-seconds 300}))

;; ============================================================================
;; Magic Link Token Generation (§9.2)
;; ============================================================================

(defn generate-magic-link!
  "Generates an HMAC-signed single-use magic link token, records it in XTDB,
   logs a security audit event, and dispatches the email.
   Options map:
     :identity          - user or admin identity (e.g. 'admin@breakwindow.lan')
     :client-ip         - IP address of the requester (default: '127.0.0.1')
     :secret            - HMAC signing key (default: get-auth-secret)
     :ttl-seconds       - validity duration in seconds (default: 900 = 15 mins)
     :base-url          - application base URL (default: 'https://breakwindow.lan')
     :send-email?       - whether to dispatch email (default: true)
     :email-opts        - additional options for bwr.auth.email
     :ip-limiter        - custom rate limiter for client IP
     :identity-limiter  - custom rate limiter for identity
     :rate-limit?       - boolean, enforce rate limits (default: false unless limiters supplied)"
  [node opts]
  (let [identity-str (:identity opts)
        _ (when-not (and (string? identity-str) (not (empty? identity-str)))
            (throw (ex-info "Missing required :identity for magic link generation" {:opts opts})))
        client-ip (get opts :client-ip "127.0.0.1")
        
        ;; 1. Check IP rate limit (§9.4)
        _ (when-let [limiter (or (:ip-limiter opts)
                                (when (:rate-limit? opts) default-ip-limiter))]
            (let [res (security/check-rate-limit! limiter client-ip)]
              (when-not (:allowed? res)
                (store-query/log-security-event!
                 node
                 {:sec-event/type :sec.type/rate-limit-exceeded
                  :sec-event/client-ip client-ip
                  :sec-event/identity identity-str
                  :sec-event/status :status/failure
                  :sec-event/reason :reason/rate-limit-ip
                  :sec-event/detail {:reset-seconds (:reset-seconds res)}})
                (throw (ex-info "Rate limit exceeded for client IP"
                                {:status 429
                                 :retry-after (:reset-seconds res)
                                 :reason :reason/rate-limit-ip})))))

        ;; 2. Check Identity rate limit (§9.4)
        _ (when-let [limiter (or (:identity-limiter opts)
                                (when (:rate-limit? opts) default-identity-limiter))]
            (let [res (security/check-rate-limit! limiter identity-str)]
              (when-not (:allowed? res)
                (store-query/log-security-event!
                 node
                 {:sec-event/type :sec.type/rate-limit-exceeded
                  :sec-event/client-ip client-ip
                  :sec-event/identity identity-str
                  :sec-event/status :status/failure
                  :sec-event/reason :reason/rate-limit-identity
                  :sec-event/detail {:reset-seconds (:reset-seconds res)}})
                (throw (ex-info "Rate limit exceeded for identity"
                                {:status 429
                                 :retry-after (:reset-seconds res)
                                 :reason :reason/rate-limit-identity})))))

        secret (or (:secret opts) (get-auth-secret))
        ttl-s (long (get opts :ttl-seconds 900))
        base-url (get opts :base-url "https://breakwindow.lan")
        now-ms (System/currentTimeMillis)
        now-inst (java.util.Date. now-ms)
        exp-ms (+ now-ms (* ttl-s 1000))
        exp-inst (java.util.Date. exp-ms)
        exp-epoch (int (/ exp-ms 1000))
        iat-epoch (int (/ now-ms 1000))
        jti (str (java.util.UUID/randomUUID))
        token-doc-id (str "token-" jti)
        claims {:sub identity-str
                :jti jti
                :exp exp-epoch
                :iat iat-epoch
                :type :auth/magic-link}
        signed-token (jwt/sign claims secret {:alg :hs256})
        magic-link-url (str base-url "/auth/verify?token=" signed-token)
        token-doc {:xt/id token-doc-id
                   :token/id token-doc-id
                   :token/jti jti
                   :token/identity identity-str
                   :token/issued-at now-inst
                   :token/expires-at exp-inst
                   :token/consumed? false
                   :token/consumed-at nil}]

    ;; 1. Transact token document into XTDB store
    (store-query/transact! node [token-doc])

    ;; 2. Log security audit event (§2.4, §9.4)
    (store-query/log-security-event!
     node
     {:sec-event/type :sec.type/magic-link-generated
      :sec-event/identity identity-str
      :sec-event/client-ip client-ip
      :sec-event/status :status/success
      :sec-event/detail {:jti jti
                         :ttl-seconds ttl-s
                         :expires-at exp-inst}})

    ;; 3. Send email if requested
    (when (get opts :send-email? true)
      (let [email-params (merge {:to identity-str
                                 :magic-link magic-link-url
                                 :expires-min (int (/ ttl-s 60))}
                                (:email-opts opts))]
        (email/send-magic-link-email! email-params)))

    {:token signed-token
     :jti jti
     :identity identity-str
     :magic-link-url magic-link-url
     :expires-at exp-inst}))

;; ============================================================================
;; Magic Link Verification & Atomic Invalidation (§9.2)
;; ============================================================================

(defn verify-and-consume!
  "Cryptographically verifies a magic link token and atomically invalidates it in XTDB.
   Distinguishes rejection causes:
     :reason/invalid-signature  - HMAC signature tampering or invalid key
     :reason/expired            - token expired past its TTL
     :reason/already-consumed   - token was previously used (single-use enforcement)
     :reason/malformed-token    - invalid token syntax
     :reason/not-found          - token record missing from store

   Returns {:valid? true :identity ... :jti ...} on success,
   or {:valid? false :reason ... :error ...} on failure."
  ([node token-str]
   (verify-and-consume! node token-str {}))
  ([node token-str opts]
   (let [secret (or (:secret opts) (get-auth-secret))
         client-ip (get opts :client-ip "127.0.0.1")]
     ;; Step 1: Verify HMAC signature and expiration claim (without hitting the DB first)
     (let [claim-result (try
                          {:ok (jwt/unsign token-str secret {:alg :hs256})}
                          (catch Exception e
                            (let [data (ex-data e)
                                  cause (:cause data)]
                              (cond
                                (= cause :signature)
                                {:error :reason/invalid-signature
                                 :msg "Signature validation failed; token has been tampered with"}

                                (= cause :exp)
                                {:error :reason/expired
                                 :msg "Magic link token has expired"}

                                :else
                                {:error :reason/malformed-token
                                 :msg (or (.getMessage e) "Malformed token format")}))))]
       (if-let [err-reason (:error claim-result)]
         ;; Signature tampering / expiration rejected before store lookup
         (do
           (store-query/log-security-event!
            node
            {:sec-event/type :sec.type/magic-link-rejected
             :sec-event/client-ip client-ip
             :sec-event/status :status/failure
             :sec-event/reason err-reason
             :sec-event/detail {:error (:msg claim-result)}})
           {:valid? false
            :reason err-reason
            :error (:msg claim-result)})

         ;; Step 2: Signature valid. Inspect token state in XTDB store
         (let [claims (:ok claim-result)
               jti (:jti claims)
               identity-str (:sub claims)
               doc-id (str "token-" jti)
               db (store-query/db-at node)
               token-doc (store-query/entity db doc-id)]
           (cond
             (nil? token-doc)
             (do
               (store-query/log-security-event!
                node
                {:sec-event/type :sec.type/magic-link-rejected
                 :sec-event/identity identity-str
                 :sec-event/client-ip client-ip
                 :sec-event/status :status/failure
                 :sec-event/reason :reason/not-found
                 :sec-event/detail {:jti jti}})
               {:valid? false
                :reason :reason/not-found
                :error "Token record not found in verified storage"})

             (:token/consumed? token-doc)
             (do
               (store-query/log-security-event!
                node
                {:sec-event/type :sec.type/magic-link-rejected
                 :sec-event/identity identity-str
                 :sec-event/client-ip client-ip
                 :sec-event/status :status/failure
                 :sec-event/reason :reason/already-consumed
                 :sec-event/detail {:jti jti
                                    :consumed-at (:token/consumed-at token-doc)}})
               {:valid? false
                :reason :reason/already-consumed
                :error "Magic link has already been used; single-use token cannot be replayed"
                :consumed-at (:token/consumed-at token-doc)})

             (.after (java.util.Date.) (:token/expires-at token-doc))
             (do
               (store-query/log-security-event!
                node
                {:sec-event/type :sec.type/magic-link-rejected
                 :sec-event/identity identity-str
                 :sec-event/client-ip client-ip
                 :sec-event/status :status/failure
                 :sec-event/reason :reason/expired
                 :sec-event/detail {:jti jti
                                    :expires-at (:token/expires-at token-doc)}})
               {:valid? false
                :reason :reason/expired
                :error "Magic link has expired"})

             :else
             ;; Step 3: Atomic check-and-invalidate via XTDB ::xt/match
             (let [now (java.util.Date.)
                   consumed-doc (assoc token-doc
                                       :token/consumed? true
                                       :token/consumed-at now)
                   tx (xt/submit-tx node [[::xt/match doc-id token-doc]
                                          [::xt/put consumed-doc]])]
               (xt/await-tx node tx)
               (if (xt/tx-committed? node tx)
                 ;; Atomically consumed successfully
                 (do
                   (store-query/log-security-event!
                    node
                    {:sec-event/type :sec.type/magic-link-verified
                     :sec-event/identity identity-str
                     :sec-event/client-ip client-ip
                     :sec-event/status :status/success
                     :sec-event/detail {:jti jti}})
                   {:valid? true
                    :identity identity-str
                    :jti jti
                    :consumed-at now})

                 ;; Race condition detected: another transaction invalidated this token
                 (do
                   (store-query/log-security-event!
                    node
                    {:sec-event/type :sec.type/magic-link-rejected
                     :sec-event/identity identity-str
                     :sec-event/client-ip client-ip
                     :sec-event/status :status/failure
                     :sec-event/reason :reason/already-consumed
                     :sec-event/detail {:jti jti
                                        :collision true}})
                   {:valid? false
                    :reason :reason/already-consumed
                    :error "Magic link consumption conflict: token was already invalidated in concurrent transaction"}))))))))))
