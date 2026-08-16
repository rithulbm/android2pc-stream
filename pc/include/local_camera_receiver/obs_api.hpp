#pragma once

#include <obs-module.h>

#include <Windows.h>

namespace lcr {

class ObsApi final {
public:
    [[nodiscard]] bool load() noexcept;
    void unload() noexcept;
    [[nodiscard]] bool available() const noexcept;

    decltype(&::obs_register_source_s) register_source_s = nullptr;
    decltype(&::obs_data_create) data_create = nullptr;
    decltype(&::obs_data_release) data_release = nullptr;
    decltype(&::obs_data_set_string) data_set_string = nullptr;
    decltype(&::obs_data_set_bool) data_set_bool = nullptr;
    decltype(&::obs_data_set_int) data_set_int = nullptr;
    decltype(&::obs_data_get_string) data_get_string = nullptr;
    decltype(&::obs_data_get_bool) data_get_bool = nullptr;
    decltype(&::obs_data_set_default_bool) data_set_default_bool = nullptr;
    decltype(&::obs_data_set_default_string) data_set_default_string = nullptr;
    decltype(&::obs_source_create_private) source_create_private = nullptr;
    decltype(&::obs_source_release) source_release = nullptr;
    decltype(&::obs_source_add_active_child) source_add_active_child = nullptr;
    decltype(&::obs_source_remove_active_child) source_remove_active_child = nullptr;
    decltype(&::obs_source_video_render) source_video_render = nullptr;
    decltype(&::obs_source_get_width) source_get_width = nullptr;
    decltype(&::obs_source_get_height) source_get_height = nullptr;
    decltype(&::obs_source_audio_pending) source_audio_pending = nullptr;
    decltype(&::obs_source_get_audio_timestamp) source_get_audio_timestamp = nullptr;
    decltype(&::obs_source_get_audio_mix) source_get_audio_mix = nullptr;
    decltype(&::obs_properties_create) properties_create = nullptr;
    decltype(&::obs_properties_add_bool) properties_add_bool = nullptr;
    decltype(&::obs_properties_add_text) properties_add_text = nullptr;
    decltype(&::obs_properties_add_button2) properties_add_button2 = nullptr;
    decltype(&::obs_property_set_long_description) property_set_long_description = nullptr;

private:
    HMODULE module_ = nullptr;
};

ObsApi &obs_api() noexcept;
[[nodiscard]] bool register_receiver_source() noexcept;

} // namespace lcr
