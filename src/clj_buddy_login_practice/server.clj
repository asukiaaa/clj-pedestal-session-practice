(ns clj-buddy-login-practice.server
  (:gen-class)
  (:require [io.pedestal.http :as http :refer [html-body]]
            [io.pedestal.http.route :as route :refer [url-for]]
            [io.pedestal.http.body-params :refer [body-params]]
            [ring.middleware.session.cookie :as cookie]
            [ring.util.response :refer [redirect]]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [hiccup.page :refer [html5]]
            [ns-tracker.core :refer [ns-tracker]]))

(def watched-namespaces (ns-tracker "src"))

(defn handler-redirect-with-injecting-data
  [_request]
  (-> (redirect (url-for :home-page-with-interceptors))
      (assoc :cookies {:some "data-in-cookie"})
      (assoc-in [:session :some] "data-in-session")))

(defn handler-page-inject-without-redirect [_request]
  {:status 200
   :cookies {:some "data-in-cookie-without-redirect"}
   :session {:some "data-in-session-without-redirect"}
   :body (html5
          [:p "injected"])})

(defn handler-page-check
  [request]
  {:status 200
   :body (html5
          [:p "check page"]
          [:p "request"]
          [:p (str request)]
          [:p "cookies"]
          [:p (str (:cookies request))]
          [:p "session"]
          [:p (str (:session request))])})

(defn handler-redirect-with-clear [_request]
  (-> (redirect (url-for :home-page-with-interceptors))
      (assoc :cookies {:some {:max-age 0}})
      (assoc :session nil)))

(defn handler-clear-without-redirect [_request]
  {:status 200
   :session nil
   :cookies {:some {:max-age 0}}
   :body (html5 [:p "clear-without-redirect"])})

(def interceptor-session (middlewares/session {:store (cookie/cookie-store)}))
(def interceptors-common [(body-params)
                          interceptor-session])

(def routes
  #{["/inject"
     :get (conj interceptors-common handler-redirect-with-injecting-data)
     :route-name :login]
    ["/inject-without-redirect"
     :get (conj interceptors-common html-body handler-page-inject-without-redirect)
     :route-name :inject-without-redirect]
    ["/home-with-interceptors"
     :get (conj interceptors-common html-body handler-page-check)
     :route-name :home-page-with-interceptors]
    ["/home-without-interceptor"
     :get [html-body handler-page-check]
     :route-name :home-page-without-interceptor]
    ["/clear"
     :get [interceptor-session handler-redirect-with-clear]
     :route-name :clear]
    ["/clear-without-redirect"
     :get [html-body interceptor-session handler-clear-without-redirect]
     :route-name :clear-without-redirect]})

(defn routes-watched []
  (doseq [ns-sym (watched-namespaces)]
    (require ns-sym :reload))
  (route/expand-routes routes))

(def service {:env                 :prod
              ::http/routes        routes
              ::http/resource-path "/public"
              ::http/type          :jetty
              ::http/port          8080})

(defonce runnable-service (http/create-server service))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& _args]
  (println "\nCreating your [DEV] server...")
  (-> service ;; start with production configuration
      (merge {:env :dev
              ::http/routes routes-watched
              ;; do not block thread that starts web server
              ::http/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ;; ::http/routes #(deref #'routes)
              ;; all origins are allowed in dev mode
              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}})
      ;; Wire up interceptor chains
      http/default-interceptors
      http/dev-interceptors
      http/create-server
      http/start))

(defn -main
  "The entry-point for 'lein run'"
  [& _args]
  (println "\nCreating your server...")
  (http/start runnable-service))
