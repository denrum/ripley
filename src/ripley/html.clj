(ns ripley.html
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (org.apache.commons.lang3 StringEscapeUtils)))

(set! *warn-on-reflection* true)

(def ^:dynamic *html-out* nil)

(defn out! [& things]
  (doseq [thing things]
    (.write ^java.io.Writer *html-out* (str thing))))

(defn dyn! [& things]
  ;; Output some dynamic part
  (.write ^java.io.Writer *html-out* (StringEscapeUtils/escapeHtml4 (str/join things))))

(if (System/getenv "RIPLEY_DEBUG")
  (defn log [& things]
    (with-open [out (io/writer (io/file "ripley.debug") :append true)]
      (.write out
              (str (str/join " " things) "\n"))))
  (defn log [& things]))

(defn to-camel-case [kw]
  (str/replace (name kw)
               #"-\w"
               (fn [m]
                 (str/upper-case (subs m 1)))))



(defn element-class-names [elt]
  (map second (re-seq #"\.([^.#]+)" (name elt))))

(defn element-name [elt]
  (second (re-find #"^([^.#]+)" (name elt))))

(defn element-id [elt]
  (second (re-find #"#([^.]+)" (name elt))))

(declare compile-html)

(defn props-and-children [body]
  (let [has-props? (map? (second body))
        props (if has-props?
                (second body)
                nil)
        children (drop (if has-props? 2 1) body)]
    [props children]))

(defn compile-children [children]
  (map compile-html children))

(defn get-key [body]
  (-> body meta :key))

(defn compile-html-element
  "Compile HTML markup element, like [:div.someclass \"content\"]."
  [body]
  (let [element-kw (first body)
        element (element-name element-kw)
        class-names (element-class-names element-kw)
        id (element-id element-kw)
        [props children] (props-and-children body)
        props (merge (when-let [k (get-key body)]
                       {:key k})
                     props
                     (when (seq class-names)
                       {:class (str/join " " class-names)})
                     (when id
                       {:id id}))]
    (log "HTML Element:" element "with props:" props "and" (count children) "children")
    `(do
       (out!
        "<" ~(name element)
        ~@(mapcat
           (fn [[attr val]]
             (when (not= val ::no-value)
               [" " (name attr) "=\"" val "\""]))
           props)
        ">")
       ~@(compile-children children)
       (out! "</" ~(name element) ">"))))

(defn compile-fragment [body]
  (let [[props children] (props-and-children body)
        key (get-key body)]
    (log "Fragment with props: " props " and key " key)
    `(do
       ~@(compile-children children))))

(defn compile-for
  "Compile special :ripley.html/for element."
  [[_ bindings body :as form]]
  (assert (vector? bindings) ":ripley.html/for bindings must be a vector")
  (assert (= 3 (count form)) ":ripley.html/for must have bindings and a single child form")
  `(doseq ~bindings
     ~(compile-html body)))

(defn compile-if
  "Compile special :ripley.html/if element."
  [[_ test then else :as form]]
  (assert (= 4 (count form)) ":ripley.html/if must have exactly 3 forms: test, then and else")
  `(if ~test
     ~(compile-html then)
     ~(compile-html else)))

(defn compile-when
  "Compile special :ripley.html/when element."
  [[_ test then :as form]]
  (assert (= 3 (count form)) ":ripley.html/when must have exactly 2 forms: test and then")
  `(when ~test
     ~(compile-html then)))

(defn compile-cond
  "Compile special :ripley.html/cond element."
  [[_ & clauses]]
  (assert (even? (count clauses)) ":ripley.html/cond must have even number of forms")
  `(cond
     ~@(mapcat (fn [[test expr]]
                 [test (compile-html expr)])
               (partition 2 clauses))))

(def compile-special {:<> compile-fragment
                      ::for compile-for
                      ::if compile-if
                      ::when compile-when
                      ::cond compile-cond})

(defn compile-html [body]
  (cond
    (vector? body)
    (cond
      ;; first element is special element
      (contains? compile-special (first body))
      ((compile-special (first body)) body)

      ;; first element is a keyword this is static HTML markup
      (keyword? (first body))
      (compile-html-element body)

      ;; unrecognized
      :else
      (throw (ex-info "Vector must start with element or special keyword. Hint: call functions with regular parenthesis."
                      {:invalid-first-element (first body)})))

    (string? body)
    ;; Static content
    `(out! ~(StringEscapeUtils/escapeHtml4 body))

    ;; Some content: a static string or symbol reference
    ;; or a list that evaluates to children
    (or (symbol? body)
        (list? body))
    `(dyn! ~body)

    :else
    (throw (ex-info (str "Can't compile to HTML: " (pr-str body))
                    {:element body}))))

(defn- optimize-nested-do
  "Remove nested do wrappings so next optimizations work better.
  Turns (do a (do b) (do c) d) into (do a b c d)."
  [[_ & forms]]
  `(do ~@(mapcat
          (fn [f]
            (if (and (seq? f)
                     (= 'do (first f)))
              (rest f)
              [f]))
          forms)))

(defn- optimize-adjacent-out! [forms]
  (loop [acc '()
         out-strings nil
         forms forms]
    (if (empty? forms)
      (concat acc
              (when (seq out-strings)
                [`(out! ~(str/join out-strings))]))
      (let [[f & forms] forms]
        (if (and (seq? f) (= 'ripley.html/out! (first f)))
          ;; This is a call to out!
          (let [[strings rest-forms] (split-with string? (rest f))]
            (if (seq strings)
              ;; There's static strings here, move them to out-strings and recur
              (recur acc
                     (concat out-strings strings)
                     (concat (when (seq rest-forms)
                               [`(out! ~@rest-forms)])
                             forms))

              ;; No static strings in the beginning here
              (let [[dynamic-parts rest-forms] (split-with (complement string?) rest-forms)]
                (recur (concat acc
                               (when (seq out-strings)
                                 [`(out! ~(str/join out-strings))])
                               [`(out! ~@dynamic-parts)])
                       nil ;; out strings consumed, if any
                       (if (seq rest-forms)
                         ;; some more strings here
                         (concat [`(out! ~@rest-forms)] forms)
                         forms)))))

          ;; This is something else, consume out strings (if any)
          (recur (concat acc
                         (when (seq out-strings)
                           [`(out! ~(str/join out-strings))])
                         [f])
                 nil
                 forms))))))

(defn- optimize
  "Optimize compiled HTML forms."
  [optimizations form]
  (walk/postwalk
   (fn [form]
     (if-not (seq? form)
       form

       (if-let [optimization-fn (optimizations (first form))]
         (optimization-fn form)
         form)))
   form))

(defmacro html
  "Compile hiccup to HTML output."
  [body]
  (->> body
       compile-html
       (optimize {'do optimize-nested-do})
       (optimize {'do optimize-adjacent-out!})))

(comment
  (with-out-str
    (binding [*html-out* clojure.core/*out*]
      (html [:div.main
             [:h3 "section"]
             [:div.second-level
              [:ul [::for [x (range 10)] [:li {:data-idx x} "<script>" x]]]]]))))