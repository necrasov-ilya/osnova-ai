# Подключённый Android-девайс

Разработка идёт на Kotlin и реальном Android-устройстве. Первое целевое устройство: Vivo X200 Ultra на Snapdragon 8 Elite.

## Главная мысль

Мы часто обновляем приложение, но не скачиваем модель заново каждый раз.

Если `applicationId` и подпись сборки не меняются, обычная установка поверх старой сборки сохраняет данные приложения. Поэтому модель, лежащая в app-specific storage, остаётся на устройстве.

## Базовая подготовка

На телефоне:

1. Включить Developer options.
2. Включить USB debugging.
3. Подключить телефон по USB.
4. Разрешить отладку с этого компьютера.

На компьютере:

```powershell
adb devices
```

Если устройство видно, можно запускать debug-сборку из Android Studio или командой:

```powershell
.\gradlew :app:installDebug
```

Если собрали APK руками:

```powershell
adb install -r path\to\app-debug.apk
```

Флаг `-r` переустанавливает приложение с сохранением данных.

## Цикл разработки

Обычный цикл:

1. Меняем Kotlin-код, UI, pipeline или документацию.
2. Собираем `debug`.
3. Ставим на Vivo X200 Ultra через Android Studio или `installDebug`.
4. Приложение стартует с уже существующим кэшем модели.
5. Проверяем камеру, очередь, распознавание и вставку в заметку.

Модель не трогаем, пока не меняется manifest модели.

## Как положить модель в debug-сборку

Для первых тестов удобен debug override.

Основной MVP скачивает Gemma сам. Ручной debug override нужен только если хочется положить файл заранее.

Папка на устройстве:

```text
/sdcard/Android/data/ai.osnova.app/files/models/gemma-4-e2b-it-sm8750/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm
```

Команды:

```powershell
adb shell mkdir -p /sdcard/Android/data/ai.osnova.app/files/models/gemma-4-e2b-it-sm8750/main
adb push .\models\gemma-4-E2B-it_qualcomm_sm8750.litertlm /sdcard/Android/data/ai.osnova.app/files/models/gemma-4-e2b-it-sm8750/main/gemma-4-E2B-it_qualcomm_sm8750.litertlm
```

`applicationId` MVP: `ai.osnova.app`.

Debug-сборка должна искать модель так:

1. debug external models dir;
2. internal models dir;
3. download manager;
4. fallback.

Production-сборка не должна зависеть от ручного `adb push`.

## Что нельзя делать без причины

- Не нажимать `Clear storage`, если не тестируем первый запуск.
- Не делать `adb uninstall`, если хотим сохранить модель.
- Не менять `applicationId` без миграционного плана.
- Не менять debug signing key без причины.
- Не класть `.litertlm` в git.
- Не зашивать E2B-модель в базовый APK.

Если нужна чистая проверка первого запуска, тогда данные можно очистить намеренно:

```powershell
adb shell pm clear ai.osnova.app
```

После этого модель придётся скачать или положить заново.

## Проверка после обновления APK

Минимальный smoke:

1. Установить приложение.
2. Скачать или положить модель.
3. Запустить короткий VLM-запрос.
4. Поставить новую debug-сборку через `installDebug`.
5. Открыть приложение.
6. Проверить, что модель найдена локально и не скачивается заново.
7. Открыть камерный контейнер.
8. Проверить очередь и Smart Insert.

В логах должны быть понятные события:

```text
ModelManager: found local model gemma4-e2b/2026-05-osnova-0
ModelManager: sha256 verified
LiteRtEngine: initialized backend=GPU
Pipeline: camera container opened
Pipeline: stable region accepted
SmartInsert: target=current_section
```

## Когда модель скачивается заново

Только в таких случаях:

- локального файла нет;
- хэш не совпал;
- manifest указывает новую версию;
- runtime требует другой формат;
- пользователь выбрал другой канал;
- данные приложения очищены;
- debug external model удалён.

Мелкое обновление UI, экранов, анимаций или pipeline не должно запускать повторную загрузку модели.

## Что логировать на Vivo X200 Ultra

- версия приложения;
- версия модели;
- backend LiteRT-LM;
- время инициализации модели;
- время первого токена;
- время полного ответа;
- температура/thermal status;
- заряд батареи;
- длина очереди;
- был ли повторный download после обновления APK.

## Источники

- [Run apps on a hardware device](https://developer.android.com/studio/run/device)
- [Build your app from the command line](https://developer.android.com/build/building-cmdline)
- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [Configure on-device developer options](https://developer.android.com/studio/debug/dev-options)
