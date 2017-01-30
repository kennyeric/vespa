// Copyright 2017 Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "buffer_type.h"
#include "bufferstate.h"
#include "datastore.h"
#include "entryref.h"
#include "i_compaction_context.h"
#include <vespa/vespalib/util/array.h>
#include <vespa/searchlib/btree/btree.h>

namespace search {
namespace datastore {

/**
 * Datastore for unique values of type EntryT that is accessed via a
 * 32-bit EntryRef.
 */
template <typename EntryT, typename RefT = EntryRefT<22> >
class UniqueStore
{
public:
    using DataStoreType = DataStoreT<RefT>;
    using EntryType = EntryT;
    using RefType = RefT;
    /*
     * Compare two values in data store based on reference.  Invalid
     * reference is mapped to local value reference to support
     * comparing with new value candidate outside data store.
     */
    class Compare {
        const DataStoreType &_store;
        const EntryType &_value;
public:
        Compare(const DataStoreType &store, const EntryType &value)
            : _store(store),
              _value(value)
        {
        }
        inline const EntryType &get(EntryRef ref) const {
            if (ref.valid()) {
                RefType iRef(ref);
                return *_store.template getBufferEntry<EntryType>(iRef.bufferId(), iRef.offset());
            } else {
                return _value;
            }
        }
        inline bool operator()(const EntryRef lhs, const EntryRef rhs) const
        {
            const EntryType &lhsValue = get(lhs);
            const EntryType &rhsValue = get(rhs);
            return lhsValue < rhsValue;
        }
    };
    using UniqueStoreBufferType = BufferType<EntryType>;
    using DictionaryTraits = btree::BTreeTraits<32, 32, 7, true>;
    using Dictionary = btree::BTree<EntryRef, uint32_t,
                                    btree::NoAggregated,
                                    Compare,
                                    DictionaryTraits>;
private:
    DataStoreType _store;
    UniqueStoreBufferType _typeHandler;
    uint32_t _typeId;
    Dictionary _dict;
    using generation_t = vespalib::GenerationHandler::generation_t;

public:
    UniqueStore();
    ~UniqueStore();
    EntryRef move(EntryRef ref);
    EntryRef add(const EntryType &value);
    const EntryType &get(EntryRef ref) const
    {
        RefType iRef(ref);
        return *_store.template getBufferEntry<EntryType>(iRef.bufferId(), iRef.offset());
    }
    void remove(EntryRef ref);
    ICompactionContext::UP compactWorst();
    MemoryUsage getMemoryUsage() const;

    // Pass on hold list management to underlying store
    void transferHoldLists(generation_t generation) { _dict.getAllocator().transferHoldLists(generation); _store.transferHoldLists(generation); }
    void trimHoldLists(generation_t firstUsed) { _dict.getAllocator().trimHoldLists(firstUsed); _store.trimHoldLists(firstUsed); }
    vespalib::GenerationHolder &getGenerationHolder(void) { return _store.getGenerationHolder(); }
    void setInitializing(bool initializing) { _store.setInitializing(initializing); }
    void freeze() { _dict.getAllocator().freeze(); }

    // Should only be used for unit testing
    const BufferState &bufferState(EntryRef ref) const;
};

}
}
