/* Exercise the real PCSX loader/error/teardown paths without a game or GPU.
 * Built with frontend/main.c and libpcsxcore/plugins.c; unused frontend
 * sections are discarded, leaving only the builtin plugin loader.
 */
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "frontend/plugin.h"
#include "libpcsxcore/plugins.h"

PcsxConfig Config;
static int initializations;
static int shutdowns;
static int cd_shutdowns;

void SysPrintf(const char *format, ...) { (void)format; }
int cdra_init(void) { return 0; }
void cdra_shutdown(void) { cd_shutdowns++; }

static long plugin_init(void) { initializations++; return 0; }
static long plugin_shutdown(void) { shutdowns++; return 0; }

void *plugin_link(enum builtint_plugins_e id, const char *symbol)
{
    (void)id;
    if (strcmp(symbol, "GPUshutdown") == 0 || strcmp(symbol, "SPUshutdown") == 0)
        return (void *)plugin_shutdown;
    return (void *)plugin_init;
}

int main(void)
{
    strcpy(Config.Gpu, "builtin_gpu");
    strcpy(Config.Spu, "builtin_spu");
    if (LoadPlugins() != 0 || initializations != 2) {
        fprintf(stderr, "builtin plugin loading failed: %s\n", SysLibError());
        return 1;
    }
    ReleasePlugins();
    if (shutdowns != 2) {
        fprintf(stderr, "initialized plugins were not shut down\n");
        return 2;
    }

    if (LoadPlugins() != 0)
        return 3;
    /* Reproduce a required-symbol failure before shutdown was resolved. */
    GPU_shutdown = NULL;
    SPU_shutdown = NULL;
    ReleasePlugins();
    ReleasePlugins();
    if (shutdowns != 2 || cd_shutdowns != 5) {
        fprintf(stderr, "partial/idempotent teardown failed\n");
        return 4;
    }
    puts("PCSX builtin plugin load/error/partial teardown passed");
    return 0;
}
