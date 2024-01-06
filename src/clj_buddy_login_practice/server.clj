(ns clj-buddy-login-practice.server
  (:gen-class)
  (:require [io.pedestal.http :as http :refer [html-body]]
            [io.pedestal.http.route :refer [url-for]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.interceptor.error :refer [error-dispatch]]
            [ring.middleware.session.cookie :as cookie]
            [ring.middleware.session.store :as store]
            [ring.util.response :refer [redirect]]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [clj-time.core :as time]
            [hiccup.page :refer [html5]]
            [buddy.sign.jwt :as jwt]
            [buddy.auth.backends :as backends]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.middleware :as auth.middleware]))

#_(def secret (nonce/random-bytes 32))
(def secret "test-secret")

(def authdata {:admin "a-secret"
               :test "t-secret"})

(def backend-auth (backends/jws {:secret secret :options {:alg :hs512}}))
; https://github.com/pedestal/pedestal/blob/master/samples/buddy-auth/src/buddy_auth/service.clj

(def interceptor-authentication
  "Port of buddy-auth's wrap-authentication middleware."
  (interceptor/interceptor
   {:name ::authenticate
    :enter (fn [ctx]
             (update ctx :request auth.middleware/authentication-request backend-auth))}))

(defn interceptor-authorization
  "Port of buddy-auth's wrap-authorization middleware."
  [backend]
  (declare ctx)
  (declare ex)
  (error-dispatch [ctx ex]
                  [{:exception-type :clojure.lang.ExceptionInfo :stage :enter}]
                  (try
                    (assoc ctx
                           :response
                           (auth.middleware/authorization-error (:request ctx)
                                                                ex
                                                                backend))
                    (catch Exception e
                      (assoc ctx ::interceptor.chain/error e)))

                  :else (assoc ctx ::interceptor.chain/error ex)))

(defn hello-world [request]
  (let [name (get-in request [:params :name] "World")]
    {:status 200 :body (str "Hello " name "!\n")}))

(defn ok [d] {:status 200 :body d})
(defn bad-request [d] {:status 400 :body d})

(defn home-api
  [request]
  (if-not (authenticated? request)
    #_(throw-unauthorized)
    (bad-request {:message "unauthorized"})
    (ok {:status "Logged"
         :message (str "hello logged user " request #_(:identity request))})))

(defn build-token-for-valid-user [username password]
  (let [valid? (some-> authdata
                       (get (keyword username))
                       (= password))]
    #_(print username password authdata)
    (when valid?
      (let [claims {:user (keyword username)
                    :exp (time/plus (time/now) (time/seconds 3600))}
            token (jwt/sign claims secret {:alg :hs512})]
        token))))

(defn login-api
  [request]
  (let [username (get-in request [:json-params :username])
        password (get-in request [:json-params :password])
        valid? (some-> authdata
                       (get (keyword username))
                       (= password))]
    #_(print username password authdata)
    (if valid?
      (let [claims {:user (keyword username)
                    :exp (time/plus (time/now) (time/seconds 3600))}
            token (jwt/sign claims secret {:alg :hs512})]
        (ok token))
      (bad-request {:message "wrong auth data"
                    ;; :req request ;; for debug
                    :username username}))))

(defn render-login-form [& {:keys [username]}]
  (html5
   [:form {:action "/login" :method :post}
    [:label {:for "name"} "username"] [:br]
    [:input {:type "text" :id "username" :name "username" :data username}] [:br]
    [:label {:for "password"} "password"] [:br]
    [:input {:type "password" :id "password" :name "password"}] [:br]
    [:input {:type "submit"}]]))

(defn handler-page-login
  [request]
  (ok (str (render-login-form)
           (html5 [:p [:a {:href (url-for :login-api)} "login api"]]
                  [:p [:a {:href (url-for :greet)} "greet"]]))))

(defn handler-page-login-post
  [request]
  (let [username (get-in request [:form-params :username])
        password (get-in request [:form-params :password])
        token (build-token-for-valid-user username password)]
    (if token
      (-> (redirect (url-for :home-page-with-interceptors))
          (assoc :cookies {:token token})
          (assoc-in [:session :token] token))
      (ok (str (render-login-form) request)))))

(defn handler-redirect-to-greet
  [request]
  (redirect (url-for :greet)))

(defn handler-page-check
  [request]
  (ok (html5
       [:p "check page"]
       [:p "request"]
       [:p (str request)]
       [:p "cookies"]
       [:p (str (:cookies request))]
       [:p "session"]
       [:p (str (:session request))]))
  #_(if-not (authenticated? request)
      #_(throw-unauthorized)
      (bad-request (html5 [:p "unauthorized"]))
      (ok (html5 [:p "hello logged user"]
                 [:pre request]))))

(def interceptor-session (middlewares/session {:store (cookie/cookie-store)}))

(def interceptors-common [(body-params)
                          interceptor-session
                          interceptor-authentication
                          (interceptor-authorization backend-auth)])

(defn handler-logout [_request]
  (-> (redirect (url-for :home-page-with-interceptors))
      #_(assoc :cookies {}) ;; does not work 
      (assoc :cookies {:token nil}) ;; soso
      #_(assoc :cookie {}) ;; does not work
      (assoc :session {})))

(defn handler-logout-without-redirect [request]
  #_(-> request ;; does not work
        (assoc :session {})
        (assoc :cookies {}))
  #_(store/delete-session (:cookies request) :token)
  {:status 200
   :session nil ;; able to clear session cache
   ;; :cookies {:token nil} ;; soso
   ;; :cookies nil ;; does not work
   :cookies {} ;; does not work
   ;; :session-store {:token "geo"} ;; does not work
   ;; :store (:token "fuga") ;; does not work
   :body (html5 [:p "logout-without-redirect"])})

(def routes
  #{#_["/login" :post (conj interceptors-common login) :route-name :login]
    ["/login"
     :get #_[(body-params) html-body handler-page-login]
     (conj interceptors-common html-body handler-page-login)
     ;:post [(body-params) handler-page-login-post]
     :route-name :login]
    ["/login"
     :post #_[(body-params) html-body
              middlewares/params
              middlewares/keyword-params
              interceptor-session
              handler-page-login-post]
     (conj interceptors-common
           html-body
           middlewares/params
           middlewares/keyword-params
           handler-page-login-post)
     :route-name :login-post]
    ["/home-with-interceptors"
     :get (conj interceptors-common html-body handler-page-check)
     :route-name :home-page-with-interceptors]
    ["/home-without-interceptor"
     :get [html-body handler-page-check]
     :route-name :home-page-without-interceptor]
    ["/redirect-to-greet"
     :get [html-body handler-redirect-to-greet]
     :route-name :recirect-to-greet]
    ["/logout"
     :get [interceptor-session handler-logout]
     :route-name :logout]
    ["/logout-without-redirect"
     :get [html-body interceptor-session
           handler-logout-without-redirect]
     :route-name :logout-without-redirect]
    ["/login-api"
     :post [(body-params) login-api]
     :route-name :login-api]
    ["/home-api-with-interceptors"
     :get (conj interceptors-common home-api)
     :route-name :home-api-with-interceptors]
    ["/home-api-without-interceptors"
     :get [(body-params) home-api]
     :route-name :home-api-without-interceptors]
    ["/home-api-with-interceptor-authentication"
     :get [(body-params) interceptor-authentication home-api]
     :route-name :home-api-with-interceptor-authentication]
    ["/home-api-with-interceptor-authorization"
     :get [(body-params) (interceptor-authorization backend-auth) home-api]
     :route-name :home-api-with-interceptor-authorization]
    ["/greet" :get hello-world :route-name :greet]
    #_["/admin/greet"
       :get [(body-params)
             html-body
             interceptor-authentication
             (interceptor-authorization backend-auth)
             hello-world]
       :route-name :admin-greet]})

(def service {:env                 :prod
              ::http/routes        routes
              ::http/resource-path "/public"
              ::http/type          :jetty
              ::http/port          8080})

(defonce runnable-service (http/create-server service))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (println "\nCreating your [DEV] server...")
  (-> service ;; start with production configuration
      (merge {:env :dev
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
  [& args]
  (println "\nCreating your server...")
  (http/start runnable-service))
