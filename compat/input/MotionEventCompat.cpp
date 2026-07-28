/*
 * Copyright (C) 2026 The Klee Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <ftl/flags.h>
#include <input/Input.h>

// Android 17 made MotionEvent flags strongly typed. Keep the former ABI for
// proprietary WFD clients and immediately convert it to the platform type.
extern "C" void initializeLegacyMotionEvent(
        android::MotionEvent* event, int32_t id, android::DeviceId deviceId, uint32_t source,
        android::ui::LogicalDisplayId displayId, std::array<uint8_t, 32> hmac, int32_t action,
        int32_t actionButton, int32_t flags, int32_t edgeFlags, int32_t metaState,
        int32_t buttonState, android::MotionClassification classification,
        const android::ui::Transform& transform, float xPrecision, float yPrecision,
        float rawXCursorPosition, float rawYCursorPosition,
        const android::ui::Transform& rawTransform, nsecs_t downTime,
        nsecs_t eventTime, size_t pointerCount,
        const android::PointerProperties* pointerProperties,
        const android::PointerCoords* pointerCoords)
        __asm__("_ZN7android11MotionEvent10initializeEiijNS_2ui16LogicalDisplayIdENSt3__15arrayIhLm32EEEiiiiiiNS_20MotionClassificationERKNS1_9TransformEffffS9_llmPKNS_17PointerPropertiesEPKNS_13PointerCoordsE");

extern "C" void initializeLegacyMotionEvent(
        android::MotionEvent* event, int32_t id, android::DeviceId deviceId, uint32_t source,
        android::ui::LogicalDisplayId displayId, std::array<uint8_t, 32> hmac, int32_t action,
        int32_t actionButton, int32_t flags, int32_t edgeFlags, int32_t metaState,
        int32_t buttonState, android::MotionClassification classification,
        const android::ui::Transform& transform, float xPrecision, float yPrecision,
        float rawXCursorPosition, float rawYCursorPosition,
        const android::ui::Transform& rawTransform, nsecs_t downTime,
        nsecs_t eventTime, size_t pointerCount,
        const android::PointerProperties* pointerProperties,
        const android::PointerCoords* pointerCoords) {
    event->initialize(id, deviceId, source, displayId, hmac, action, actionButton,
                      android::ftl::Flags<android::MotionFlag>{flags}, edgeFlags, metaState,
                      buttonState, classification, transform, xPrecision, yPrecision,
                      rawXCursorPosition, rawYCursorPosition, rawTransform, downTime, eventTime,
                      pointerCount, pointerProperties, pointerCoords);
}
