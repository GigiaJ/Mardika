(ns starter.browser
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom :as dom]
            [ajax.core :refer [GET json-response-format]]
            [cljs.core.async :refer [chan timeout put! <! go-loop]]
            [starter.env :refer [env]]
))

(println env)

(defn get-url [url]
  (let [c (chan)]
    (js/console.log "Fetching movies for URL:" url)
    (GET url
      {:response-format (json-response-format {:keywords? true})
       :handler #(do
                   (js/console.log "Received response:" (js->clj %))
                   (put! c %))
       :error-handler #(do
                         (js/console.error "Error occurred:" %)
                         (put! c nil))})
    (go-loop []
      (let [response (<! c)]
        (println "Processing: " (:results response))
        (if (nil? response)
          (do
            (js/console.log "Response is nil, retrying in 1 second...")
            (<! (timeout 1000)) ; Wait for 1 second before retrying
            (recur)) ; Retry
          (let [results  (:results response)]
            (js/console.log "Dispatching results:" results)
            (rf/dispatch [:update-items results])))))))

(defn fetch-movies [page]
  (let [url (str (env :base-url) "/movie/popular?api_key=" (env :api-key) "&page=" page)]
    (js/console.log "Fetching movies for page" page "with URL:" url)
    (get-url url)))

(rf/reg-event-db
 :initialize
 (fn [_ _]
   (js/console.log "Initializing state")
   (let [initial-state {:items [] :loading? true :page 1}]
     (fetch-movies 1)
     initial-state)))

(rf/reg-event-db
 :update-items
 (fn [db [_ new-items]]
   (js/console.log "Updating items with:" new-items)
   (if (seq new-items)
     (-> db
         (update :items concat new-items)
         (update :page inc)
         (assoc :loading? false))
     (assoc db :loading? false))))

(rf/reg-event-db
 :fetch-more-items
 (fn [db _]
   (let [page (:page db)]
     (js/console.log "Dispatching fetch-more-items for page" page)
     (fetch-movies page)
     (assoc db :loading? true))))

;; Trigger fetch-more-items event on scroll
(rf/reg-event-fx
 :scroll-handler
 (fn [{:keys [db]} _]
   (js/console.log "Scroll handler triggered")
   (when (<= (- (.-scrollHeight (.-documentElement js/document))
                (.-scrollTop (.-documentElement js/document))
                (.-clientHeight (.-documentElement js/document))) 200) ; Increased threshold
     (js/console.log "Almost at the bottom of the page")
     (when-not (:loading? db)
       {:db (assoc db :loading? true)
        :dispatch [:fetch-more-items]}))))

;; Add scroll event listener
(defn handle-scroll []
  (js/console.log "Scroll event detected")
  (rf/dispatch [:scroll-handler]))

(rf/reg-event-db
 :search-movies
 (fn [db [_ query]]
   (let [url (str (env :base-url)  "/search/movie?api_key=" (env :api-key) "&query=" query)]
     (js/console.log "Searching movies for query:" query "with URL:" url)
     (get-url url)
     (when-not (:loading? db)
       {:db (assoc db :loading? true)
        :dispatch [:fetch-more-items]}))))

(defn search-bar []
  (let [query (r/atom "")]
    (fn []
      [:div
       [:input {:type "text"
                :placeholder "Search for a movie..."
                :value @query
                :on-change #(reset! query (-> % .-target .-value))}]
       [:button {:on-click #(rf/dispatch [:search-movies @query])} "Search"]])))

(defn expanded-interface [props]
  [:div
   {:style {:position "fixed" :top 0 :left 0 :width "100%" :height "100%" :background-color "white" :z-index 1000 :overflow "auto"}}
   [:button {:on-click #(rf/dispatch [:collapse-item])} "Close"]
   [:h3 (:title props)]
   [:p (:overview props)]
   [:iframe {:allowFullScreen true
             :autoPlay true
             :src (str (env :vid-src)  (:id props))
             :style {:width "100%"
                     :height "85%"}}]])

(rf/reg-sub
 :expanded-item
 (fn [db _]
   (println "Getting" (:expanded-item db))
   (:expanded-item db)))

(rf/reg-event-db
 :expand-item
 (fn [db [_ item-id]]
   (js/console.log "Setting expanded-item:" item-id)
   (assoc db :expanded-item item-id)))

(rf/reg-event-db
 :collapse-item
 (fn [db _]
   (dissoc db :expanded-item)))

;; Subscriptions
(rf/reg-sub
 :items
 (fn [db _]
   (:items db)))

(rf/reg-sub
 :loading?
 (fn [db _]
   (:loading? db)))

(defn with-hover [component]
  (fn [props]
    (let [hovered (r/atom false)
          expanded-item @(rf/subscribe [:expanded-item])]
      (fn [props]
        (println expanded-item)
        [:<>
         ;; Render the expanded interface if this item is expanded
         (if (= (:id props) @(rf/subscribe [:expanded-item]))
           [expanded-interface props]
           ;; Otherwise, handle hover logic and normal view
           [:div.item {:on-mouse-enter #(reset! hovered true)
                       :on-mouse-leave #(reset! hovered false)
                       :style {:background-color (if @hovered "lightblue" "lightgray")
                               :position "relative"
                               :width "100%"
                               :height "100%"}}
            ;; Show hover interface
            (if @hovered
              [:div
               {:on-click #((println (:id props))
                            (rf/dispatch [:expand-item (:id props)]))}
               ;; Hover overlay content
               [:div {:class "overlay"}
                ;; Title
                [:h3 (:title props)]
                ;; Description
                [:p (:overview props)]]
               [:img {:src (str "https://image.tmdb.org/t/p/w342" (:poster_path props))}]]
              ;; Default view for non-hovered state
              [component props])])]))))

(defn item-component [props]
  [:div.item
   [:img {:src (str "https://image.tmdb.org/t/p/w342" (:poster_path props))
          :id (str "cover-img" (:id props))}]])

(defn item-components [items]
  [:div.items-container
   (for [item items]
     ^{:key (:id item)}
     [(with-hover item-component) item])])

(defn chunk-items [items chunk-size]
  (map-indexed (fn [index chunk]
                 {:id index :items chunk})
               (partition-all chunk-size items)))

;; Components
(defn main-page []
  (r/create-class
   {:component-did-mount
    (fn []
      (js/console.log "Component did mount")
      (js/addEventListener "scroll" handle-scroll))
    :component-will-unmount
    (fn []
      (js/console.log "Component will unmount")
      (js/removeEventListener "scroll" handle-scroll))
    :reagent-render
    (fn []
      (let [items @(rf/subscribe [:items])
            loading? @(rf/subscribe [:loading?])]
        (js/console.log "Rendering items:" items)
        [:div
         [:h1 "Endless Scrolling TMDB Movies"]
         [:div [search-bar]]
         (for [chunk (chunk-items items 4)] ;; Chunk items into groups of 4
           ^{:key (:id chunk)} ;; Use the unique :id for the key
           [item-components (:items chunk)])
         (when loading?
           [:div "Loading..."])]))}))

(defn init []
  (rf/dispatch-sync [:initialize])
  (dom/render [main-page] (.getElementById js/document "app")))

;; Initialize app
(init)
