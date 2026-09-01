/* Единица трансляции для single-header декодеров (реализация только тут). */
#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO   /* файлы открываем сами по fd — SAF отдаёт content:// */
#include "dr_flac.h"

#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "dr_wav.h"
