(ns server
  (:require [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [compojure.core :refer [routes defroutes GET POST]]
            [org.httpkit.server :as server]))

(defroutes app-routes
  (GET "/api/data" [] {:status 200 :headers {"Content-Type" "application/json"} :body "{\"message\": \"Something I guess\"}"})
  (POST "/api/data" [data] {:status 200 :headers {"Content-Type" "application/json"} :body (str "{\"received\": \"" data "\"}")})
  (GET "*" [] {:status 404 :headers {"Content-Type" "text/plain"} :body "Not Found"}))

(def app
  (-> (routes app-routes)
      (wrap-resource "")
      (wrap-defaults site-defaults)))

(defn -main []
  (println "Server running at http://localhost:8080")
  (server/run-server app {:port 8080}))
