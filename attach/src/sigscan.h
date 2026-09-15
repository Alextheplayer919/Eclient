#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string_view>

namespace sigscan {

/**
 * @brief Is a native library already mapped in this process?
 */
bool isModuleLoaded(std::string_view moduleName);

/**
 * @brief Byte-pattern ("XX XX ? XX") scan of the executable segments of a
 *        module loaded in this process. Returns the absolute address or 0.
 */
uintptr_t scan(std::string_view pattern, std::string_view moduleName);

} // namespace sigscan
