#include "local_camera_receiver/obs_api.hpp"

#include <array>

namespace lcr {
namespace {

template <typename Function>
bool resolve(HMODULE module, Function &slot, const char *name) noexcept
{
    slot = reinterpret_cast<Function>(GetProcAddress(module, name));
    return slot != nullptr;
}

} // namespace

bool ObsApi::load() noexcept
{
    if (module_ != nullptr) {
        return available();
    }
    module_ = GetModuleHandleW(L"obs.dll");
    return module_ != nullptr &&
        resolve(module_, register_source_s, "obs_register_source_s") &&
        resolve(module_, data_create, "obs_data_create") &&
        resolve(module_, data_release, "obs_data_release") &&
        resolve(module_, data_set_string, "obs_data_set_string") &&
        resolve(module_, data_set_bool, "obs_data_set_bool") &&
        resolve(module_, data_set_int, "obs_data_set_int") &&
        resolve(module_, data_get_string, "obs_data_get_string") &&
        resolve(module_, data_get_bool, "obs_data_get_bool") &&
        resolve(module_, data_set_default_bool, "obs_data_set_default_bool") &&
        resolve(module_, data_set_default_string, "obs_data_set_default_string") &&
        resolve(module_, source_create_private, "obs_source_create_private") &&
        resolve(module_, source_release, "obs_source_release") &&
        resolve(module_, source_get_settings, "obs_source_get_settings") &&
        resolve(module_, source_update, "obs_source_update") &&
        resolve(module_, source_add_active_child, "obs_source_add_active_child") &&
        resolve(module_, source_remove_active_child, "obs_source_remove_active_child") &&
        resolve(module_, source_video_render, "obs_source_video_render") &&
        resolve(module_, source_get_width, "obs_source_get_width") &&
        resolve(module_, source_get_height, "obs_source_get_height") &&
        resolve(module_, source_get_frame, "obs_source_get_frame") &&
        resolve(module_, source_release_frame, "obs_source_release_frame") &&
        resolve(module_, source_audio_pending, "obs_source_audio_pending") &&
        resolve(module_, source_get_audio_timestamp, "obs_source_get_audio_timestamp") &&
        resolve(module_, source_get_audio_mix, "obs_source_get_audio_mix") &&
        resolve(module_, properties_create, "obs_properties_create") &&
        resolve(module_, properties_add_bool, "obs_properties_add_bool") &&
        resolve(module_, properties_add_text, "obs_properties_add_text") &&
        resolve(module_, properties_add_button2, "obs_properties_add_button2") &&
        resolve(module_, property_set_long_description, "obs_property_set_long_description");
}

void ObsApi::unload() noexcept
{
    *this = ObsApi{};
}

bool ObsApi::available() const noexcept
{
    return module_ != nullptr && register_source_s != nullptr && source_create_private != nullptr;
}

ObsApi &obs_api() noexcept
{
    static ObsApi api;
    return api;
}

} // namespace lcr
