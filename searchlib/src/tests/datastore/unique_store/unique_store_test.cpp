// Copyright 2017 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("unique_store_test");
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/vespalib/test/insertion_operators.h>
#include <vespa/vespalib/util/traits.h>
#include <vespa/searchlib/datastore/unique_store.hpp>
#include <vespa/searchlib/test/datastore/memstats.h>
#include <vector>

using namespace search::datastore;
using search::MemoryUsage;
using vespalib::ArrayRef;
using generation_t = vespalib::GenerationHandler::generation_t;
using MemStats = search::datastore::test::MemStats;

template <typename EntryT, typename RefT = EntryRefT<22> >
struct Fixture
{
    using EntryRefType = RefT;
    using UniqueStoreType = UniqueStore<EntryT, RefT>;
    using value_type = EntryT;
    using ReferenceStore = std::map<EntryRef, std::pair<EntryT,uint32_t>>;

    UniqueStoreType store;
    ReferenceStore refStore;
    generation_t generation;
    Fixture()
        : store(),
          refStore(),
          generation(1)
    {}
    void assertAdd(const EntryT &input) {
        EntryRef ref = add(input);
        assertGet(ref, input);
    }
    EntryRef add(const EntryT &input) {
        EntryRef result = store.add(input);
        auto insres = refStore.insert(std::make_pair(result, std::make_pair(input, 1u)));
        if (!insres.second) {
            ++insres.first->second.second;
        }
        return result;
    }
    void assertGet(EntryRef ref, const EntryT &exp) const {
        EntryT act = store.get(ref);
        EXPECT_EQUAL(exp, act);
    }
    void remove(EntryRef ref) {
        ASSERT_EQUAL(1u, refStore.count(ref));
        store.remove(ref);
        if (refStore[ref].second > 1) {
            --refStore[ref].second;
        } else {
            refStore.erase(ref);
        }
    }
    void remove(const EntryT &input) {
        remove(getEntryRef(input));
    }
    uint32_t getBufferId(EntryRef ref) const {
        return EntryRefType(ref).bufferId();
    }
    void assertBufferState(EntryRef ref, const MemStats expStats) const {
        EXPECT_EQUAL(expStats._used, store.bufferState(ref).size());
        EXPECT_EQUAL(expStats._hold, store.bufferState(ref).getHoldElems());
        EXPECT_EQUAL(expStats._dead, store.bufferState(ref).getDeadElems());
    }
    void assertMemoryUsage(const MemStats expStats) const {
        MemoryUsage act = store.getMemoryUsage();
        EXPECT_EQUAL(expStats._used, act.usedBytes());
        EXPECT_EQUAL(expStats._hold, act.allocatedBytesOnHold());
        EXPECT_EQUAL(expStats._dead, act.deadBytes());
    }
    void assertStoreContent() const {
        for (const auto &elem : refStore) {
            TEST_DO(assertGet(elem.first, elem.second.first));
        }
    }
    EntryRef getEntryRef(const EntryT &input) {
        for (const auto &elem : refStore) {
            if (elem.second.first == input) {
                return elem.first;
            }
        }
        return EntryRef();
    }
    void trimHoldLists() {
        store.freeze();
        store.transferHoldLists(generation++);
        store.trimHoldLists(generation);
    }
    void compactWorst() {
        ICompactionContext::UP ctx = store.compactWorst();
        std::vector<EntryRef> refs;
        for (const auto &elem : refStore) {
            refs.push_back(elem.first);
        }
        refs.push_back(EntryRef());
        std::vector<EntryRef> compactedRefs = refs;
        ctx->compact(ArrayRef<EntryRef>(compactedRefs));
        ASSERT_FALSE(refs.back().valid());
        refs.pop_back();
        ReferenceStore compactedRefStore;
        for (size_t i = 0; i < refs.size(); ++i) {
            ASSERT_EQUAL(0u, compactedRefStore.count(compactedRefs[i]));
            ASSERT_EQUAL(1u, refStore.count(refs[i]));
            compactedRefStore.insert(std::make_pair(compactedRefs[i], refStore[refs[i]]));
        }
        refStore = compactedRefStore;
    }
    size_t entrySize() const { return sizeof(EntryT); }
};

using NumberFixture = Fixture<uint32_t>;
using StringFixture = Fixture<std::string>;
using SmallOffsetNumberFixture = Fixture<uint32_t, EntryRefT<10>>;

TEST("require that we test with trivial and non-trivial types")
{
    EXPECT_TRUE(vespalib::can_skip_destruction<NumberFixture::value_type>::value);
    EXPECT_FALSE(vespalib::can_skip_destruction<StringFixture::value_type>::value);
}

TEST_F("require that we can add and get values of trivial type", NumberFixture)
{
    TEST_DO(f.assertAdd(1));
    TEST_DO(f.assertAdd(2));
    TEST_DO(f.assertAdd(3));
    TEST_DO(f.assertAdd(1));
}

TEST_F("require that we can add and get values of non-trivial type", StringFixture)
{
    TEST_DO(f.assertAdd("aa"));
    TEST_DO(f.assertAdd("bbb"));
    TEST_DO(f.assertAdd("ccc"));
    TEST_DO(f.assertAdd("aa"));
}

TEST_F("require that elements are put on hold when value is removed", NumberFixture)
{
    EntryRef ref = f.add(1);
    // Note: The first buffer have the first element reserved -> we expect 2 elements used here.
    TEST_DO(f.assertBufferState(ref, MemStats().used(2).hold(0).dead(1)));
    f.store.remove(ref);
    TEST_DO(f.assertBufferState(ref, MemStats().used(2).hold(1).dead(1)));
}

TEST_F("require that elements are reference counted", NumberFixture)
{
    EntryRef ref = f.add(1);
    EntryRef ref2 = f.add(1);
    EXPECT_EQUAL(ref.ref(), ref2.ref());
    // Note: The first buffer have the first element reserved -> we expect 2 elements used here.
    TEST_DO(f.assertBufferState(ref, MemStats().used(2).hold(0).dead(1)));
    f.store.remove(ref);
    TEST_DO(f.assertBufferState(ref, MemStats().used(2).hold(0).dead(1)));
    f.store.remove(ref);
    TEST_DO(f.assertBufferState(ref, MemStats().used(2).hold(1).dead(1)));
}

TEST_F("require that new underlying buffer is allocated when current is full", SmallOffsetNumberFixture)
{
    uint32_t firstBufferId = f.getBufferId(f.add(1));
    for (uint32_t i = 0; i < (F1::EntryRefType::offsetSize() - 2); ++i) {
        uint32_t bufferId = f.getBufferId(f.add(i + 2));
        EXPECT_EQUAL(firstBufferId, bufferId);
    }
    TEST_DO(f.assertStoreContent());

    uint32_t bias = F1::EntryRefType::offsetSize();
    uint32_t secondBufferId = f.getBufferId(f.add(bias + 1));
    EXPECT_NOT_EQUAL(firstBufferId, secondBufferId);
    for (uint32_t i = 0; i < 10u; ++i) {
        uint32_t bufferId = f.getBufferId(f.add(bias + i + 2));
        EXPECT_EQUAL(secondBufferId, bufferId);
    }
    TEST_DO(f.assertStoreContent());
}

TEST_F("require that compaction works", NumberFixture)
{
    EntryRef val1Ref = f.add(1);
    EntryRef val2Ref = f.add(2);
    f.remove(f.add(4));
    f.trimHoldLists();
    TEST_DO(f.assertBufferState(val1Ref, MemStats().used(4).dead(2))); // Note: First element is reserved
    uint32_t val1BufferId = f.getBufferId(val1Ref);

    EXPECT_EQUAL(2u, f.refStore.size());
    f.compactWorst();
    EXPECT_EQUAL(2u, f.refStore.size());
    TEST_DO(f.assertStoreContent());

    // Buffer has been compacted
    EXPECT_NOT_EQUAL(val1BufferId, f.getBufferId(f.getEntryRef(1)));
    // Old ref should still point to data.
    f.assertGet(val1Ref, 1);
    f.assertGet(val2Ref, 2);
    EXPECT_TRUE(f.store.bufferState(val1Ref).isOnHold());
    f.trimHoldLists();
    EXPECT_TRUE(f.store.bufferState(val1Ref).isFree());
    TEST_DO(f.assertStoreContent());
}

TEST_MAIN() { TEST_RUN_ALL(); }
