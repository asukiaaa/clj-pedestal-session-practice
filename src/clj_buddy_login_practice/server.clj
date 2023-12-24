(ns clj-buddy-login-practice.server
  (:gen-class)
  (:require [io.pedestal.http :as http :refer [html-body]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.interceptor.chain :as interceptor.chain]
            [io.pedestal.interceptor.error :refer [error-dispatch]]
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

(defn home
  [request]
  (if-not (authenticated? request)
    #_(throw-unauthorized)
    (bad-request {:message "unauthorized"})
    (ok {:status "Logged"
         :message (str "hello logged user " (:identity request))})))

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
      (bad-request {:message "wrong auth data" :pass password :use username :req request}))))

(def interceptors-common [(body-params)
                          interceptor-authentication
                          (interceptor-authorization backend-auth)])

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
  (ok (render-login-form)))

(defn handler-page-login-post
  [request]
  (let [username (get-in request [:form-params :username])
        password (get-in request [:form-params :password])
        valid? (some-> authdata
                       (get (keyword username))
                       (= password))]
    (if valid?
      (ok "loggedin")
      (ok (str (render-login-form) request)))))

(defn handler-page-check
  [request]
  (ok "hoihoi"))

(def routes
  #{["/"
     :get (conj interceptors-common home) :route-name :home]
    #_["/login" :post (conj interceptors-common login) :route-name :login]
    ["/login"
     :get [(body-params) html-body handler-page-login]
     ;:post [(body-params) handler-page-login-post]
     :route-name :login]
    ["/login"
     :post [(body-params) html-body handler-page-login-post]
     :route-name :login-post]
    ["/login-api"
     :post [(body-params) login-api]
     :route-name :login-api]
    ["/check-without-interceptors" :get [(body-params) handler-page-check] :route-name :check-without-interceptors]
    ["/greet" :get hello-world :route-name :greet]
    ["/admin/greet"
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
