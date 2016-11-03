# Compost

Library that manages lifecycle of stateful components. 
This is a variation of https://github.com/stuartsierra/component project's idea.
At the moment this library does not support ClojureScript.

## Status

Beta, you are welcome to try it. 

## Usage

See tests for examples. Component declaration has form
```clojure
   {:requires #{:required-component-id-1 :required-component-id-2}}
    :this initial-state
    :get (fn [this] ...) ;; Returns value of this component that other components will get as dependency. 
    :start (fn [this dependency-components-map] ...) ;; Acquire resources (open connections, start threads ...)
    :stop (fn [this] ...)} ;; Release resources.
```
All fields are optional, defaults are:
```clojure
   {:requires #{}
    :this nil
    :get identity 
    :start (fn [this dependency-components-map] this)
    :stop identity}
```
`:start` and `:stop` functions should return new value of component's `:this`.
If component acquires resources in `:start` it must release them in `:stop`. 
If `:stop` function is present then it must handle gracefully `(stop (stop component))` case.
System declaration is a plain map
```clojure
  {:component-1-id component-1-declaration
   :component-2-id component-2-declaration
   ...
  }
```

Lifecycle usage example
```clojure
 (let [s (compost/start system-map #{:web-component :some-worker-component})]
   (Thread/sleep 5000)
   (compost/stop s))
```

### Using stuartsierra.component components

You can adapt existing components as follows:

```clojure
(defn component-using [init using]
  {:requires (set using)
   :this init
   :start (fn [this deps]
            (-> (merge this deps)
                component/start)
   :stop component/stop})
 
(def system 
  {:conn-source (component-using
                   (->MyConnPool)
                   [])
   :dao (component-using
          (map->MyDbComponent {})
          [:conn-source])}
```

## Motivation

I like what com.stuartsierra.component provides but I also want
* Use any value as component. E.g. often I want a function closure to be a component. 
  Or, alternatively, a component be visible as a function. Besides this, I do not like the idea of  
  always keeping dependency reference even though it might be needed only in start function. 
* Do not require a new type for each component. Requirement to implement Lifecycle often
  gets in the way when you only need an ad-hock component. Also requirement for component 
  to be a map and implement LifeCycle effectively requires component to be a record. 
  This also means that sometimes people resort to work-arounds to avoid creating new types.
* Use plain Clojure data structures to configure system. I think that putting configuration into metadata
  was a mistake. Instead of streamlining it actually complicates code. With this approach, it's not 
  clear for reader what happens when you configure system and why it requires helper functions to do that.
* Provide default cleanup procedure. This is rather an opinion part. I want a default procedure 
  that tries to stop system in case of partial start.
* Be compatible with com.stuartsierra.component/Lifecycle components. 
  There are already lots of such components and this is a good thing. 
  This part should require only small amount of glue code.

## License 

Copyright © Petr Gladkikh <PetrGlad@gmail.com>

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
