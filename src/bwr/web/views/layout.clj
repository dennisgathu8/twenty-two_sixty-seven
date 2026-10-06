(ns bwr.web.views.layout
  "Base HTML shell and security scaffolding (§8, §9) for server-rendered views.
   Provides strict Content-Security-Policy (CSP), Subresource Integrity (SRI) hashing,
   semantic HTML5 structure, and machine-legible Open Graph / JSON-LD metadata."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [hiccup.core :as h]
            [hiccup.page :as hp]
            [hiccup.util :as hu]
            [ring.util.response :as resp]
            [bwr.security.middleware :as sec]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Security Scaffolding: CSP & SRI (§9.3)
;; ============================================================================

(def csp-policy
  "Strict Content-Security-Policy delegated to bwr.security.middleware."
  sec/csp-policy)

(def security-headers
  "Standard security response headers delegated to bwr.security.middleware."
  sec/security-headers)

(defn compute-sri-hash
  "Computes a Subresource Integrity (SRI) sha384 hash string for text or byte content."
  [content-or-bytes]
  (let [digest (java.security.MessageDigest/getInstance "SHA-384")
        bytes (if (string? content-or-bytes)
                (.getBytes ^String content-or-bytes java.nio.charset.StandardCharsets/UTF_8)
                ^bytes content-or-bytes)
        hash (.digest digest bytes)
        b64 (.encodeToString (java.util.Base64/getEncoder) hash)]
    (str "sha384-" b64)))

(def main-css-sri
  "Cached SRI sha384 hash of the local main.css stylesheet."
  (delay
    (if-let [res (io/resource "public/css/main.css")]
      (compute-sri-hash (slurp res :encoding "UTF-8"))
      (compute-sri-hash "/* fallback */"))))

;; ============================================================================
;; Base HTML Shell Layout (§8)
;; ============================================================================

(defn base-layout
  "Renders a complete, semantic HTML5 document wrapped around the provided body content.
   Accepts an options map with:
     :title         - Page title string
     :description   - Meta description and og:description
     :og-type       - Open Graph type (default: 'website')
     :canonical-url - Canonical URL for the page
     :json-ld       - Clojure data structure to serialize as application/ld+json"
  ([opts & body-content]
   (let [page-title (or (:title opts) "Break-Window Response")
         page-desc (or (:description opts)
                       "Decision support for FIFA 2026 mandatory hydration breaks (22' & 67').")
         og-type (or (:og-type opts) "website")
         json-ld-data (:json-ld opts)
         sri-val @main-css-sri]
     (str
      (h/html
       (hp/doctype :html5)
       [:html {:lang "en"}
        [:head
         [:meta {:charset "utf-8"}]
         [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
         [:meta {:http-equiv "Content-Security-Policy" :content csp-policy}]
         [:meta {:name "description" :content page-desc}]

         ;; Open Graph tags (§8 Machine Legibility)
         [:meta {:property "og:title" :content page-title}]
         [:meta {:property "og:description" :content page-desc}]
         [:meta {:property "og:type" :content og-type}]
         [:meta {:property "og:site_name" :content "Break-Window Response"}]
         (when-let [url (:canonical-url opts)]
           [:meta {:property "og:url" :content url}])

         ;; JSON-LD Structured Data
         (when json-ld-data
           [:script {:type "application/ld+json"}
            (hu/raw-string (json/write-str json-ld-data))])

         [:title page-title]

         ;; SRI-protected local stylesheet (§9.3)
         [:link {:rel "stylesheet"
                 :href "/css/main.css"
                 :integrity sri-val
                 :crossorigin "anonymous"}]]

        [:body
         [:header.site-header
          [:div.site-brand
           [:a {:href "/"} "Break-Window Response"]
           [:span "FIFA 2026 Decision Support (22' & 67')"]]
          [:nav.site-nav
           [:a {:href "/"} "Matches"]
           [:a {:href "/health"} "System Status"]]]

         [:main#content.container
          body-content]

         [:footer.site-footer
          [:p "Break-Window Response — Parens to Production architecture."]
          [:p "Deterministic decision support: XTDB bi-temporal storage, core.logic relations, server-rendered views."]]]])))))

;; ============================================================================
;; Ring Response Helpers
;; ============================================================================

(defn html-response
  "Returns a Ring 200 OK response with semantic HTML body and full security headers."
  [html-string]
  (-> (resp/response html-string)
      (resp/content-type "text/html; charset=utf-8")
      (update :headers merge security-headers)))

(defn error-response
  "Renders an error page with appropriate status code (400, 404, etc.) and security headers."
  [status title message]
  (let [body (base-layout
              {:title (str status " " title " — Break-Window Response")
               :description message}
              [:div.card
               [:h1 (str status " — " title)]
               [:p.error-message message]
               [:div.error-actions
                [:a.breadcrumb {:href "/"} "← Return to Home"]]])]
    (-> (resp/response body)
        (resp/status status)
        (resp/content-type "text/html; charset=utf-8")
        (update :headers merge security-headers))))
