(ns bwr.auth.session
  "Authenticated session management, verification, and revocation (§9.2, §9.4).
   Issues HMAC-signed session tokens backed by stateful XTDB session records,
   enabling server-side revocation (logout, admin termination) and immediate
   invalidation. Provides Ring authentication and authorization middleware."
  (:require [clojure.tools.logging :as log]
             [clojure.string :as str]
             [buddy.sign.jwt :as jwt]
             [xtdb.api :as xt]
             [bwr.store.schema :as schema]
             [bwr.store.query :as store-query]
             [bwr.auth.magic-link :as magic-link]
             [bwr.security.middleware :as security]))

(set! *warn-on-reflection* true)

(def default-session-ttl-seconds
  "Default session duration: 8 hours (28,800 seconds)."
  28800)

(def session-cookie-name
  "Standard cookie name for Break-Window Response sessions."
  "bwr_session")

;; ============================================================================
;; Session Creation (§9.2)
;; ============================================================================

(defn create-session!
  "Creates an authenticated session for an identity.
   Persists a session record in XTDB and returns a signed JWT token.
   Options:
     :ttl-seconds - session validity duration in seconds (default: 8 hours)
     :client-ip   - requester client IP address
     :user-agent  - client User-Agent string
     :secret      - HMAC signing key (default: magic-link/get-auth-secret)"
  ([node identity-str]
   (create-session! node identity-str {}))
  ([node identity-str opts]
   (let [sid (str (java.util.UUID/randomUUID))
         session-id (str "session-" sid)
         secret (or (:secret opts) (magic-link/get-auth-secret))
         ttl-s (long (get opts :ttl-seconds default-session-ttl-seconds))
         now-ms (System/currentTimeMillis)
         now-inst (java.util.Date. now-ms)
         exp-ms (+ now-ms (* ttl-s 1000))
         exp-inst (java.util.Date. exp-ms)
         exp-epoch (int (/ exp-ms 1000))
         iat-epoch (int (/ now-ms 1000))
         client-ip (get opts :client-ip "127.0.0.1")
         user-agent (get opts :user-agent "unknown")
         claims {:sid sid
                 :sub identity-str
                 :iat iat-epoch
                 :exp exp-epoch
                 :type :auth/session}
         signed-token (jwt/sign claims secret {:alg :hs256})
         session-doc {:xt/id session-id
                      :session/id session-id
                      :session/token signed-token
                      :session/identity identity-str
                      :session/created-at now-inst
                      :session/expires-at exp-inst
                      :session/revoked? false
                      :session/revoked-at nil
                      :session/client-ip client-ip
                      :session/user-agent user-agent}]

     ;; 1. Transact session into XTDB
     (store-query/transact! node [session-doc])

     ;; 2. Log security audit event
     (store-query/log-security-event!
      node
      {:sec-event/type :sec.type/session-created
       :sec-event/identity identity-str
       :sec-event/client-ip client-ip
       :sec-event/status :status/success
       :sec-event/detail {:session-id session-id
                          :sid sid
                          :ttl-seconds ttl-s
                          :expires-at exp-inst}})

     {:session-id session-id
      :token signed-token
      :identity identity-str
      :created-at now-inst
      :expires-at exp-inst})))

;; ============================================================================
;; Session Verification (§9.2)
;; ============================================================================

(defn verify-session
  "Verifies a session token or session ID against the XTDB store.
   Checks cryptographic integrity, expiration, and server-side revocation.
   Returns:
     {:valid? true :identity ... :session-id ... :session ...} on success
     {:valid? false :reason ... :error ...} on rejection."
  ([node token-or-id]
   (verify-session node token-or-id {}))
  ([node token-or-id opts]
   (if (or (nil? token-or-id) (str/blank? token-or-id))
     {:valid? false
      :reason :reason/missing-token
      :error "No session token provided"}
     (let [secret (or (:secret opts) (magic-link/get-auth-secret))
           session-id (if (str/starts-with? token-or-id "session-")
                        token-or-id
                        ;; Decode JWT claims to extract sid
                        (try
                          (let [claims (jwt/unsign token-or-id secret {:alg :hs256})]
                            (str "session-" (:sid claims)))
                          (catch Exception e
                            nil)))]
       (if (nil? session-id)
         {:valid? false
          :reason :reason/invalid-signature
          :error "Session token signature is invalid or malformed"}
         (let [db (store-query/db-at node)
               session-doc (store-query/entity db session-id)]
           (cond
             (nil? session-doc)
             {:valid? false
              :reason :reason/not-found
              :error "Session record not found in verified storage"}

             (:session/revoked? session-doc)
             {:valid? false
              :reason :reason/revoked
              :error "Session has been explicitly revoked (logged out)"}

             (.after (java.util.Date.) (:session/expires-at session-doc))
             {:valid? false
              :reason :reason/expired
              :error "Session has expired"}

             :else
             {:valid? true
              :identity (:session/identity session-doc)
              :session-id session-id
              :session session-doc})))))))

;; ============================================================================
;; Session Revocation / Invalidation (§9.2)
;; ============================================================================

(defn revoke-session!
  "Revokes an active session immediately in XTDB.
   Subsequent requests bearing this session token will be rejected.
   Options:
     :reason    - keyword or string description of revocation cause
     :client-ip - IP address of the client triggering revocation"
  ([node token-or-id]
   (revoke-session! node token-or-id {}))
  ([node token-or-id opts]
   (let [secret (or (:secret opts) (magic-link/get-auth-secret))
         client-ip (get opts :client-ip "127.0.0.1")
         reason (get opts :reason :reason/user-logout)
         session-id (if (str/starts-with? token-or-id "session-")
                      token-or-id
                      (try
                        (let [claims (jwt/unsign token-or-id secret {:alg :hs256})]
                          (str "session-" (:sid claims)))
                        (catch Exception _ nil)))]
     (if-not session-id
       false
       (let [db (store-query/db-at node)
             session-doc (store-query/entity db session-id)]
         (if-not session-doc
           false
           (let [now (java.util.Date.)
                 revoked-doc (assoc session-doc
                                    :session/revoked? true
                                    :session/revoked-at now)
                 tx (xt/submit-tx node [[::xt/match session-id session-doc]
                                        [::xt/put revoked-doc]])]
             (xt/await-tx node tx)
             (when (xt/tx-committed? node tx)
               (store-query/log-security-event!
                node
                {:sec-event/type :sec.type/session-revoked
                 :sec-event/identity (:session/identity session-doc)
                 :sec-event/client-ip client-ip
                 :sec-event/status :status/success
                 :sec-event/reason reason
                 :sec-event/detail {:session-id session-id
                                    :revoked-at now}})
               true))))))))

;; ============================================================================
;; Cookie & Header Helpers
;; ============================================================================

(defn extract-session-token
  "Extracts a session token from request cookies, Authorization header, or x-bwr-session header."
  [request]
  (or
   ;; 1. Standard Ring cookies map
   (when-let [c (get-in request [:cookies session-cookie-name])]
     (if (map? c) (:value c) c))

   ;; 2. Raw Cookie header parsing
   (when-let [cookie-header (get-in request [:headers "cookie"])]
     (let [pairs (str/split cookie-header #";\s*")]
       (some (fn [pair]
               (let [[k v] (str/split pair #"=" 2)]
                 (when (= (str/trim k) session-cookie-name)
                   (str/trim v))))
             pairs)))

   ;; 3. Authorization Bearer token
   (when-let [auth (get-in request [:headers "authorization"])]
     (when (str/starts-with? (str/trim auth) "Bearer ")
       (str/trim (subs (str/trim auth) 7))))

   ;; 4. Custom header fallback
   (get-in request [:headers "x-bwr-session"])))

(defn build-session-cookie
  "Generates Set-Cookie specification map for a new session."
  [token opts]
  {:value token
   :path "/"
   :http-only true
   :same-site :lax
   :secure (get opts :secure? false)
   :max-age (long (get opts :ttl-seconds default-session-ttl-seconds))})

(defn clear-session-cookie
  "Generates Set-Cookie specification map to clear/delete the session cookie."
  []
  {:value ""
   :path "/"
   :http-only true
   :same-site :lax
   :max-age 0
   :expires "Thu, 01 Jan 1970 00:00:00 GMT"})

;; ============================================================================
;; Ring Authentication & Authorization Middleware (§9.2, §9.3)
;; ============================================================================

(defn wrap-session-auth
  "Ring middleware that populates :identity and :session into request if a valid session exists.
   Does NOT reject unauthenticated requests; downstream handlers can inspect (:identity request)."
  ([handler node] (wrap-session-auth handler node {}))
  ([handler node opts]
   (fn [request]
     (let [token (extract-session-token request)]
       (if-not token
         (handler request)
         (let [auth-res (verify-session node token opts)]
           (if (:valid? auth-res)
             (handler (assoc request
                             :identity (:identity auth-res)
                             :session (:session auth-res)
                             :session-id (:session-id auth-res)))
             ;; Invalid/expired session: strip and pass through
             (handler request))))))))

(defn wrap-require-auth
  "Ring middleware that gates access behind authentication.
   If unauthenticated, returns HTTP 401 Unauthorized (with security headers)
   and logs an unauthorized access attempt to the XTDB audit log."
  ([handler node] (wrap-require-auth handler node {}))
  ([handler node opts]
   (fn [request]
     (let [client-ip (security/extract-client-ip request)
           token (extract-session-token request)
           auth-res (when token (verify-session node token opts))]
       (if (and auth-res (:valid? auth-res))
         (handler (assoc request
                         :identity (:identity auth-res)
                         :session (:session auth-res)
                         :session-id (:session-id auth-res)))
         ;; Unauthorized access attempt
         (do
           (when node
             (store-query/log-security-event!
              node
              {:sec-event/type :sec.type/unauthorized-access-blocked
               :sec-event/client-ip client-ip
               :sec-event/status :status/failure
               :sec-event/reason (or (:reason auth-res) :reason/unauthenticated)
               :sec-event/detail {:uri (:uri request)
                                  :method (:request-method request)}}))
           {:status 401
            :headers (merge security/security-headers
                            {"Content-Type" "text/plain; charset=utf-8"})
            :body "Unauthorized: Valid authenticated session required"}))))))

;; ============================================================================
;; Unified Magic-Link-to-Session Handshake (§9.2)
;; ============================================================================

(defn verify-magic-link-and-create-session!
  "Convenience handshake: verifies and atomically consumes a magic link token,
   and upon success immediately creates an authenticated XTDB session.
   Returns:
     {:valid? true :identity ... :session-id ... :session-token ... :token ...}
   or {:valid? false :reason ... :error ...}."
  ([node token-str]
   (verify-magic-link-and-create-session! node token-str {}))
  ([node token-str opts]
   (let [verify-res (magic-link/verify-and-consume! node token-str opts)]
     (if (:valid? verify-res)
       (let [session-res (create-session! node (:identity verify-res) opts)]
         (merge verify-res
                {:session-id (:session-id session-res)
                 :session-token (:token session-res)
                 :session-expires-at (:expires-at session-res)}))
       verify-res))))

