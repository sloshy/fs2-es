---
layout: docs
title: KeyedState
permalink: docs/typeclasses/keyedstate/
---
# KeyedState
When dealing with event-driven state, you typically have one of two configurations.
Either you have one, large set of state that is based on the same event log, or you have multiple states, organized by key.
For the first case, it is fairly easy to get by using just [`EventLog`](eventlog.md), especially if you make use of the `attachLog` operator.
For other cases, you need evidence that your states contain keys, and that you can sort through your incoming events to see which events apply to which states.

This is defined in `KeyedState`, which is simply a combination of [`Keyed`](keyed.md) and [`Driven`](driven.md).
Like `Driven`, there is also a `KeyedStateNonEmpty` variant available for cases where there is no "empty state" to model.
This type class does not define any additional functionality, but it is particularly useful for cases where you need to filter by key and apply state at the same time.

For examples on where it could be used, see [`EventLog`](eventlog.md) and [`EventStateCache`](eventstatecache.md)
