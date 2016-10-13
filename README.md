# pendel

Library that manages lifecycle of stateful components. 
This is a variation of https://github.com/stuartsierra/component project.

## Status

Early prototype. Missing parts:
1. Proper error handling (especially expect components' code exceptions), startup recovery procedure.
2. Use loggers instead of println.
3. More tests.
4. Unify start/stop procedure similar to com.stuartsierra.component implementation.
5. (Maybe. Concurrent start/stop as long as dependency graph allows.)

## Usage

See tests for examples. Component declaration has form
```clojure
   {:requires #{:required-component-id-1 :required-component-id-2}}
    :this initial-state
    :get (fn [this] ...) ;; Determines what other components will get as value of this component. 
    :start (fn [this dependency-components-map] ...) ;; Acquire resources (open connections, start threads ...)
    :stop (fn [this] ...)} ;; Release resources.
```
All fields are optional. Component defaults are:
```clojure
   {:requires #{}
    :this nil
    :get (fn [this] this) 
    :start (fn [this dependency-components-map])
    :stop (fn [this])}
```
If component acquires resources in `:start` it must release them in `:stop`. 
If `:stop` function is present then it must handle gracefully `(stop (stop component))` case.
System declaration is a plain map
```clojure
  {:component-1-id component-1-declaraion
   :component-2-id component-2-declaraion
  }
```

Lifecycle usage example
```clojure
 (let [s (pendel/start system-map #{:web-component :some-worker-component})]
   (Thread/sleep 5000)
   (pendel/stop s))
```

## Motivation

I like what com.stuartsierra.component provides but I also want
* Use any value as component. E.g. often I want a function closure to be a component. 
  Or, alternatively, a component be visible as a function. 
* Do not require a new type for each component. Requirement to implement Lifecycle often
  gets in the way when you only need an ad-hock component. This also means that sometimes people try
  to avoid creating new types and instead of composition use e.g. macroses to generate component code.
* Use plain Clojure data structures to configure system. I think that putting configuration into metadata
  was a mistake. Instead of streamlining it actually complicates code. With this approach, it's not 
  clear for casual reader what happens when you configure system and why it requires helper functions to do that.
* Provide default cleanup procedure. This is rather an opinion part. I want a default procedure 
  that tries to stop system in case of partial start.
* Be potentially compatible with com.stuartsierra.component/Lifecycle components. 
  There are already lots of such components and this is a good thing. 
  This part should require only small amount of glue code.

## License 

Copyright © Petr Gladkikh <PetrGlad@gmail.com>

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
