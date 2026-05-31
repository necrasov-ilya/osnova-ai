# Доставка моделей

Модель не должна ехать внутри каждого APK. Иначе каждое маленькое изменение UI будет превращаться в тяжёлую переустановку, а разработка быстро станет неприятной.

## Решение

Код приложения и модели живут отдельно.

APK/AAB содержит:

- Kotlin-код;
- UI;
- pipeline;
- LiteRT-LM интеграцию;
- лёгкий manifest моделей;
- fallback-логику.

Модель хранится как отдельный артефакт:

- `.litertlm`;
- версия;
- размер;
- `sha256`;
- совместимая версия runtime;
- URL или локальный debug-путь;
- статус: скачана, проверена, активна, устарела.

Обычное обновление приложения не должно скачивать модель заново. Модель скачивается только если её нет, изменилась версия/хэш, пользователь выбрал другой канал или данные приложения были очищены.

## Где хранить на устройстве

Production-путь:

```text
context.filesDir/models/{modelId}/{modelVersion}/model.litertlm
```

Это app-specific internal storage. Он подходит для артефактов, которые имеют смысл только для OSNOVA AI.

Debug-путь:

```text
context.getExternalFilesDir("models")/{modelId}/{modelVersion}/model.litertlm
```

Он удобнее для разработки с подключённым устройством, потому что туда проще один раз положить модель через `adb push`. В debug-сборке можно сначала проверять внешний debug-путь, потом внутренний production-кэш.

## Что происходит при обновлении приложения

При обычном обновлении APK через Android Studio, Gradle или `adb install -r` данные приложения сохраняются. Значит, уже скачанная модель остаётся на устройстве.

Модель пропадёт или станет недоступной, если:

- приложение удалить без сохранения данных;
- очистить данные приложения в настройках Android;
- поменять `applicationId`;
- поставить сборку с другой подписью поверх старой и получить конфликт;
- поменять схему хранения без миграции;
- пользователь или система удалит внешний debug-кэш.

## Manifest моделей

Черновой формат:

```json
{
  "activeModelId": "gemma4-e2b",
  "models": [
    {
      "id": "gemma-4-e2b-it",
      "version": "main",
      "runtime": "litert-lm",
      "format": "litertlm",
      "fileName": "gemma-4-E2B-it.litertlm",
      "sizeBytes": 2588147712,
      "sha256": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
      "minAppVersion": 1,
      "downloadUrl": "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    }
  ]
}
```

`ModelManager` сравнивает manifest с локальным состоянием. Если активная модель уже есть и хэш совпадает, она используется без скачивания.

SM8750 NPU-файл хранится как отдельный кандидат:

```text
gemma-4-E2B-it_qualcomm_sm8750.litertlm
sizeBytes = 3016294400
sha256 = 41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e
```

Его нельзя считать основным MVP-файлом, пока запуск NPU не прошёл smoke-тест на Vivo X200 Ultra.

## Обновление модели

Обновление модели делаем атомарно:

1. Скачать новую модель во временную папку.
2. Проверить размер и `sha256`.
3. Проверить совместимость runtime.
4. Сделать короткий smoke-запрос.
5. Переключить `activeModelVersion`.
6. Старую модель удалить позже, когда новая точно работает.

Не удалять старую модель до полной проверки новой. Иначе плохая загрузка превращается в сломанный on-device режим.

## Чем скачивать

Для MVP:

- собственный `ModelDownloader` на Kotlin;
- `WorkManager` для длинной пользовательской задачи;
- foreground notification с прогрессом;
- докачка после обрыва через HTTP range, если сервер поддерживает;
- проверка `sha256` после скачивания.

`DownloadManager` можно рассмотреть для простого HTTP download, но для больших моделей нам важны контроль, валидация, логирование и понятное состояние в UI.

Play Asset Delivery полезен для публикации через Google Play, но у него есть ограничения по размерам asset pack. Для E2B-моделей в несколько гигабайт надо отдельно проверять, помещается ли модель в лимиты и можно ли удобно обновлять её как asset. Для ранней разработки лучше не завязываться на PAD.

Firebase ML Model Downloader хорош для custom TensorFlow Lite моделей и умеет обновлять модель отдельно от приложения. Для `.litertlm`-моделей Gemma через LiteRT-LM он не основной путь, но его поведение полезно как ориентир: локальная модель, фоновое обновление, хэш, безопасная замена.

## Каналы

Нужны разные каналы модели:

- `dev` - локальная модель на подключённом девайсе;
- `preview` - тестовая модель для внутренней сборки;
- `stable` - модель по умолчанию;
- `fallback` - серверный маршрут или лёгкая текстовая модель.

Смена канала не должна требовать переустановки приложения.

## Правило для разработки

Каждый раз докачивать модель на телефон не надо.

Нормальный цикл:

1. Один раз положить или скачать модель.
2. Много раз обновлять APK.
3. Использовать уже лежащую модель.
4. Перекачивать только при смене версии модели или очистке данных.

## Источники

- [Android Debug Bridge: `adb install -r` сохраняет данные приложения](https://developer.android.com/tools/adb)
- [App-specific storage](https://developer.android.com/training/data-storage/app-specific)
- [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery)
- [Google Play app size limits](https://support.google.com/googleplay/android-developer/answer/9859372)
- [WorkManager long-running workers](https://developer.android.com/guide/background/persistent/how-to/long-running)
- [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)
- [Firebase ML custom models](https://firebase.google.com/docs/ml/android/use-custom-models)
