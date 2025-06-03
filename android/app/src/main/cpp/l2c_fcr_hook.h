#pragma once

#include <cstdint>
#include <vector>

typedef int (*HookFunType)(void *func, void *replace, void **backup);

typedef int (*UnhookFunType)(void *func);

typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

[[maybe_unused]] typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);

typedef struct t_l2c_ccb tL2C_CCB;
typedef struct t_l2c_lcb tL2C_LCB;

uintptr_t loadHookOffset(const char* package_name);
uintptr_t getModuleBase(const char *module_name);
uintptr_t loadL2cuProcessCfgReqOffset();
uintptr_t loadL2cCsmConfigOffset();
uintptr_t loadL2cuSendPeerInfoReqOffset();
bool findAndHookFunction(const char *library_path);
