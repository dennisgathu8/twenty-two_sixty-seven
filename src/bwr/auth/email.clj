(ns bwr.auth.email
  "Email delivery component for single-use magic links (§4, §9.2).
   Supports both live Mailpit SMTP delivery (localhost:1025) and an in-memory
   delivery spool for hermetic, deterministic test verification."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Email Address & Header Injection Validation (§9.2)
;; ============================================================================

(def email-regex
  "Standard RFC 5322 compliant regex for practical email address validation."
  #"^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$")

(s/def ::safe-email
  (s/and string?
         #(<= 3 (count %) 254)
         #(not (re-find #"[\r\n\x00]" %))
         #(not (re-find #"(?i)%0[ad]" %))
         #(re-matches email-regex %)))

(defn valid-email?
  "Returns true if email is well-formed and contains no CRLF or injection characters."
  [email]
  (boolean (and (string? email) (s/valid? ::safe-email (str/trim email)))))

(defn validate-email!
  "Validates email string. Throws ex-info if invalid or contains header injection characters."
  [email]
  (if (valid-email? email)
    (str/trim email)
    (throw (ex-info "Invalid email address format or header injection characters detected"
                    {:email email
                     :reason :reason/invalid-email-format}))))

(defonce ^{:doc "In-memory spool capturing sent emails for test verification and inspection."}
  delivery-spool
  (atom []))

(defn clear-spool!
  "Clears the in-memory email delivery spool."
  []
  (reset! delivery-spool []))

(defn get-spooled-emails
  "Returns all captured emails from the delivery spool."
  []
  @delivery-spool)

(defn- send-via-smtp!
  "Sends an email via direct TCP socket connection to an SMTP server (e.g. Mailpit on port 1025)."
  [{:keys [host port from to subject body-text body-html]}]
  (let [smtp-host (or host "127.0.0.1")
        smtp-port (int (or port 1025))]
    (with-open [socket (java.net.Socket. ^String smtp-host smtp-port)
                reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream socket) "UTF-8"))
                writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream socket) "UTF-8"))]
      (let [read-line-fn (fn [] (.readLine reader))
            write-line-fn (fn [^String s]
                            (.write writer s)
                            (.write writer "\r\n")
                            (.flush writer))]
        ;; Expect 220 banner
        (read-line-fn)
        (write-line-fn "HELO breakwindow.lan")
        (read-line-fn)
        (write-line-fn (str "MAIL FROM:<" from ">"))
        (read-line-fn)
        (write-line-fn (str "RCPT TO:<" to ">"))
        (read-line-fn)
        (write-line-fn "DATA")
        (read-line-fn)
        ;; Headers
        (write-line-fn (str "From: " from))
        (write-line-fn (str "To: " to))
        (write-line-fn (str "Subject: " subject))
        (write-line-fn "MIME-Version: 1.0")
        (write-line-fn "Content-Type: multipart/alternative; boundary=\"==_bwr_boundary_==\"")
        (write-line-fn "")
        ;; Text part
        (write-line-fn "--==_bwr_boundary_==")
        (write-line-fn "Content-Type: text/plain; charset=utf-8")
        (write-line-fn "")
        (write-line-fn body-text)
        (write-line-fn "")
        ;; HTML part
        (write-line-fn "--==_bwr_boundary_==")
        (write-line-fn "Content-Type: text/html; charset=utf-8")
        (write-line-fn "")
        (write-line-fn body-html)
        (write-line-fn "")
        (write-line-fn "--==_bwr_boundary_==--")
        (write-line-fn ".")
        (read-line-fn)
        (write-line-fn "QUIT")
        (read-line-fn)
        {:status :sent :backend :smtp :host smtp-host :port smtp-port}))))

(defn send-magic-link-email!
  "Sends a magic link login email to the specified identity.
   Options map:
     :backend     - :smtp or :spool (default: :spool for test safety)
     :to          - recipient email address
     :magic-link  - complete magic link URL (e.g. https://breakwindow.lan/auth/verify?token=...)
     :expires-min - TTL in minutes (default: 15)
     :smtp-host   - SMTP server host (default: 127.0.0.1)
     :smtp-port   - SMTP server port (default: 1025 for Mailpit)"
  ([opts]
   (let [backend (get opts :backend :spool)
         to (validate-email! (:to opts))
         from (validate-email! (get opts :from "noreply@breakwindow.lan"))
         link (:magic-link opts)
         ttl-min (get opts :expires-min 15)
         subject "Your Break-Window Response Access Link"
         body-text (str "Hello,\n\n"
                        "Use the following link to log in to Break-Window Response:\n\n"
                        link "\n\n"
                        "This link will expire in " ttl-min " minutes and can only be used once.\n"
                        "If you did not request this link, you can safely ignore this email.\n")
         body-html (str "<!DOCTYPE html><html><body style=\"font-family: sans-serif; line-height: 1.5;\">"
                        "<h2>Break-Window Response Access</h2>"
                        "<p>Click the link below to access your administrator session:</p>"
                        "<p><a href=\"" link "\" style=\"display: inline-block; padding: 10px 18px; background: #2563eb; color: #fff; text-decoration: none; border-radius: 4px;\">Sign In to Break-Window Response</a></p>"
                        "<p style=\"color: #64748b; font-size: 0.9em;\">This link expires in " ttl-min " minutes and can only be used once.<br/>"
                        "Direct URL: " link "</p>"
                        "</body></html>")
         message {:to to
                  :from from
                  :subject subject
                  :body-text body-text
                  :body-html body-html
                  :magic-link link
                  :sent-at (java.util.Date.)}]
     ;; Always record in delivery spool for audit and verification
     (swap! delivery-spool conj message)
     (if (= backend :smtp)
       (try
         (send-via-smtp! {:host (:smtp-host opts)
                          :port (:smtp-port opts)
                          :from from
                          :to to
                          :subject subject
                          :body-text body-text
                          :body-html body-html})
         (catch Exception e
           (log/warn e "Could not connect to SMTP server; recorded in delivery spool.")
           {:status :spooled-fallback :error (.getMessage e)}))
       {:status :spooled :message message}))))
