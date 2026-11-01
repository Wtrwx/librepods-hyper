// Exercise the production hook, including logging, with a fake Bluetooth entry point.
// Including the implementation keeps test-only access to its static state out of the ABI.
#include "../../main/cpp/l2c_fcr_hook.cpp"

#include <cstdio>

namespace {
struct OriginalCall {
    int count = 0;
    tSDP_DI_RECORD *record_pointer = nullptr;
    uint32_t *handle_pointer = nullptr;
    tSDP_DI_RECORD record{};
    tBTA_STATUS status = BTA_SUCCESS;
    bool mutate_record = false;
    bool different_status_after_first_call = false;
} observed;

constexpr uint32_t expected_handle = 0x12345678;
int failures = 0;

#define CHECK(condition) do { \
    if (!(condition)) { \
        std::fprintf(stderr, "%s:%d: %s\n", __func__, __LINE__, #condition); \
        ++failures; \
    } \
} while (false)

tBTA_STATUS fake_original(tSDP_DI_RECORD *record, uint32_t *handle) {
    ++observed.count;
    observed.record_pointer = record;
    observed.handle_pointer = handle;
    if (record) {
        observed.record = *record;
        if (observed.mutate_record) record->product = 0xffff;
    }
    if (handle) *handle = expected_handle;
    if (observed.different_status_after_first_call && observed.count > 1) return BTA_BUSY;
    return observed.status;
}

void reset(bool enabled, tBTA_STATUS status = BTA_SUCCESS) {
    observed = {};
    observed.status = status;
    original_BTA_DmSetLocalDiRecord = fake_original;
    enableSdpHook.store(enabled, std::memory_order_relaxed);
}

tSDP_DI_RECORD example_record() {
    tSDP_DI_RECORD record{};
    record.vendor = 0x038f;
    record.vendor_id_source = 0x0002;
    record.product = 0x1234;
    record.version = 0x5678;
    record.primary_record = true;
    std::strcpy(record.client_executable_url, "https://example.invalid/client");
    std::strcpy(record.service_description, "Original device information");
    std::strcpy(record.documentation_url, "https://example.invalid/docs");
    // Preserve the complete arrays, not just their null-terminated prefixes.
    record.service_description[399] = 'x';
    return record;
}

bool same_record(const tSDP_DI_RECORD &a, const tSDP_DI_RECORD &b) {
    return a.vendor == b.vendor && a.vendor_id_source == b.vendor_id_source &&
           a.product == b.product && a.version == b.version &&
           a.primary_record == b.primary_record &&
           std::memcmp(a.client_executable_url, b.client_executable_url, sizeof(a.client_executable_url)) == 0 &&
           std::memcmp(a.service_description, b.service_description, sizeof(a.service_description)) == 0 &&
           std::memcmp(a.documentation_url, b.documentation_url, sizeof(a.documentation_url)) == 0;
}

void disabled_forwards_original_once() {
    reset(false);
    auto record = example_record();
    const auto before = record;
    uint32_t handle = 0;
    CHECK(fake_BTA_DmSetLocalDiRecord(&record, &handle) == BTA_SUCCESS);
    CHECK(observed.count == 1);
    CHECK(observed.record_pointer == &record);
    CHECK(same_record(observed.record, before));
    CHECK(same_record(record, before));
    CHECK(observed.handle_pointer == &handle);
    CHECK(handle == expected_handle);
}

void enabled_only_changes_identity_in_copy() {
    reset(true);
    auto record = example_record();
    const auto before = record;
    auto expected = record;
    expected.vendor = 0x004c;
    expected.vendor_id_source = 0x0001;
    uint32_t handle = 0;
    CHECK(fake_BTA_DmSetLocalDiRecord(&record, &handle) == BTA_SUCCESS);
    CHECK(observed.count == 1);
    CHECK(observed.record_pointer != &record);
    CHECK(same_record(observed.record, expected));
    CHECK(same_record(record, before));
    CHECK(observed.handle_pointer == &handle);
    CHECK(handle == expected_handle);
}

void preserves_all_return_statuses() {
    const tBTA_STATUS statuses[] = {BTA_SUCCESS, BTA_FAILURE, BTA_PENDING,
                                   BTA_BUSY, BTA_NO_RESOURCES, BTA_WRONG_MODE};
    for (bool enabled : {false, true}) {
        for (auto status : statuses) {
            reset(enabled, status);
            observed.different_status_after_first_call = true;
            auto record = example_record();
            uint32_t handle = 0;
            CHECK(fake_BTA_DmSetLocalDiRecord(&record, &handle) == status);
            CHECK(observed.count == 1);
            CHECK(handle == expected_handle);
        }
    }
}

void missing_original_fails_without_mutation() {
    for (bool enabled : {false, true}) {
        reset(enabled);
        original_BTA_DmSetLocalDiRecord = nullptr;
        auto record = example_record();
        const auto before = record;
        uint32_t handle = 99;
        CHECK(fake_BTA_DmSetLocalDiRecord(&record, &handle) == BTA_FAILURE);
        CHECK(same_record(record, before));
        CHECK(handle == 99);
        CHECK(fake_BTA_DmSetLocalDiRecord(nullptr, nullptr) == BTA_FAILURE);
        CHECK(observed.count == 0);
    }
}

void forwards_null_arguments_once() {
    for (bool enabled : {false, true}) {
        reset(enabled, BTA_WRONG_MODE);
        uint32_t handle = 0;
        CHECK(fake_BTA_DmSetLocalDiRecord(nullptr, &handle) == BTA_WRONG_MODE);
        CHECK(observed.count == 1);
        CHECK(observed.record_pointer == nullptr);
        CHECK(observed.handle_pointer == &handle);
        CHECK(handle == expected_handle);

        reset(enabled, BTA_FAILURE);
        auto record = example_record();
        CHECK(fake_BTA_DmSetLocalDiRecord(&record, nullptr) == BTA_FAILURE);
        CHECK(observed.count == 1);
        CHECK(observed.handle_pointer == nullptr);

        reset(enabled, BTA_FAILURE);
        CHECK(fake_BTA_DmSetLocalDiRecord(nullptr, nullptr) == BTA_FAILURE);
        CHECK(observed.count == 1);
        CHECK(observed.record_pointer == nullptr);
        CHECK(observed.handle_pointer == nullptr);
    }
}

void enabled_isolates_writes_to_temporary_record() {
    reset(true);
    observed.mutate_record = true;
    auto record = example_record();
    const auto before = record;
    uint32_t handle = 0;
    fake_BTA_DmSetLocalDiRecord(&record, &handle);
    CHECK(observed.count == 1);
    CHECK(same_record(record, before));
}

void disabled_preserves_original_write_semantics() {
    reset(false);
    observed.mutate_record = true;
    auto record = example_record();
    auto expected = record;
    expected.product = 0xffff;
    uint32_t handle = 0;
    fake_BTA_DmSetLocalDiRecord(&record, &handle);
    CHECK(observed.count == 1);
    CHECK(observed.record_pointer == &record);
    CHECK(same_record(record, expected));
}

void disabling_restores_original_identity() {
    reset(true);
    auto record = example_record();
    const auto before = record;
    uint32_t handle = 0;
    fake_BTA_DmSetLocalDiRecord(&record, &handle);
    CHECK(observed.count == 1);
    CHECK(observed.record.vendor == 0x004c);
    reset(false);
    fake_BTA_DmSetLocalDiRecord(&record, &handle);
    CHECK(observed.count == 1);
    CHECK(same_record(observed.record, before));
    CHECK(same_record(record, before));
}
} // namespace

int main() {
    const struct { const char *name; void (*run)(); } tests[] = {
        {"disabled_forwards_original_once", disabled_forwards_original_once},
        {"enabled_only_changes_identity_in_copy", enabled_only_changes_identity_in_copy},
        {"preserves_all_return_statuses", preserves_all_return_statuses},
        {"missing_original_fails_without_mutation", missing_original_fails_without_mutation},
        {"forwards_null_arguments_once", forwards_null_arguments_once},
        {"enabled_isolates_writes_to_temporary_record", enabled_isolates_writes_to_temporary_record},
        {"disabled_preserves_original_write_semantics", disabled_preserves_original_write_semantics},
        {"disabling_restores_original_identity", disabling_restores_original_identity},
    };
    int passed = 0;
    for (const auto &test : tests) {
        const int before = failures;
        test.run();
        const bool success = before == failures;
        passed += success;
        std::printf("%s %s\n", success ? "PASS" : "FAIL", test.name);
    }
    std::printf("%d/%zu tests passed; %d failed assertions\n", passed, sizeof(tests) / sizeof(tests[0]), failures);
    return failures ? 1 : 0;
}
