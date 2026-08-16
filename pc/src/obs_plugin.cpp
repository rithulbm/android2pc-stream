#include "local_camera_receiver/obs_api.hpp"

OBS_DECLARE_MODULE()

MODULE_EXPORT const char *obs_module_name(void)
{
    return "Local Camera Receiver";
}

MODULE_EXPORT const char *obs_module_description(void)
{
    return "Receives an authenticated encrypted Android camera stream over local Wi-Fi.";
}

MODULE_EXPORT const char *obs_module_author(void)
{
    return "Local Camera Receiver contributors";
}

MODULE_EXPORT bool obs_module_load(void)
{
    return lcr::obs_api().load() && lcr::register_receiver_source();
}

MODULE_EXPORT void obs_module_unload(void)
{
    lcr::obs_api().unload();
}

