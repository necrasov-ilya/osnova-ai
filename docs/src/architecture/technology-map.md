# Карта технологий

Нам нужен не один большой искусственный интеллект, а несколько слоёв. Чем дешевле слой, тем чаще он может работать.

## Решение на сейчас

Базовая ставка: Android + CameraX + ML Kit для дешёвого on-device анализа, отдельные тяжёлые маршруты для VLM/LLM и строгий gate между ними.

Важный принцип: тяжёлая модель не смотрит live stream напрямую. Она получает только стабильные, полезные и уже отфильтрованные регионы.

## Технологическая карточка

| Вопрос | Решение для MVP |
| --- | --- |
| Как получать кадры | CameraX `Preview` + `ImageAnalysis` |
| Как не забить очередь кадрами | `STRATEGY_KEEP_ONLY_LATEST`, новые кадры дропаются пока analyzer занят |
| Что работает чаще всего | дешёвый gate: размер, резкость, изменение, bbox, наличие текста |
| Чем быстро искать текст | ML Kit Text Recognition v2 |
| Чем грубо понять сцену | ML Kit Object Detection/Tracking + свои признаки кадра |
| Что делать с почерком на доске | не Digital Ink, а VLM/OCR по изображению |
| Чем делать VLM в MVP | серверный VLM или отдельный экспериментальный Android-маршрут |
| Чем делать сборку заметки | LLM по событиям, не по каждому кадру |
| Как вставлять в нужное место | Note State Engine + Smart Insert |
| Как беречь телефон | не запускать анализ до раскрытия камеры, ограничивать sampler, следить за thermal status/headroom |

## Слои

| Слой | Для чего нужен | Когда работает | Кандидаты |
| --- | --- | --- | --- |
| UI и заметки | Главный экран, редактор, локальное состояние | Почти всегда | Jetpack Compose, Material 3, Room/DataStore |
| Камера | Preview и кадры для анализа | Только когда контейнер камеры раскрыт | CameraX Preview + ImageAnalysis |
| Дешёвый анализ | Понять, есть ли смысл обрабатывать кадр | Только при активной камере | ML Kit Text Recognition, Object Detection, простые CV-метрики |
| Stability gate | Не гонять модели на шум и повторы | На каждом выбранном кадре | blur score, hash/embedding, bbox stability |
| OCR | Быстро вытащить печатный текст | Когда кадр похож на текст | ML Kit Text Recognition, позже отдельный OCR для кириллицы если качество слабое |
| VLM | Понять почерк, схему, слайд, формулу, экран | Только после gate | Gemma E2B через LiteRT-LM, серверный VLM как fallback |
| LLM | Собрать черновик и аккуратно вставить в заметку | По событиям, не по кадрам | LiteRT-LM модель для простого режима, серверный LLM для качества |
| Note engine | Понять, куда вставлять блок | При каждом готовом черновике | собственная логика + LLM с контекстом заметки |

## Что брать в первый прототип

1. CameraX для камеры и `ImageAnalysis`.
2. `STRATEGY_KEEP_ONLY_LATEST`, чтобы не копить очередь кадров.
3. ML Kit Text Recognition как быстрый OCR/детектор текста.
4. Простые CV-метрики для gate: резкость, изменение кадра, стабильность региона.
5. Отдельная очередь задач, куда попадают только стабильные события.
6. Gemma E2B через LiteRT-LM как главный on-device VLM/LLM эксперимент.
7. Серверный VLM/LLM как fallback, пока не проверим on-device модели на реальных телефонах.
8. Android Thermal API как сигнал для автоматического понижения режима.

Так мы быстрее проверим UX и логику заметки, не упираясь сразу в портирование тяжёлой модели на Android.

## Что осторожно

ML Kit Digital Ink Recognition не решает задачу почерка с доски. Он работает со stroke-данными, когда пользователь пишет пальцем или стилусом по экрану. Для камеры с доской нужен OCR/VLM по изображению.

ML Kit Text Recognition подходит как быстрый слой для печатного текста и первичного сигнала, но качество зависит от разрешения, фокуса и размера символов. Для реального времени надо уменьшать разрешение, дропать кадры, пока detector занят, и не пытаться распознать всё подряд.

FastVLM интересен как ориентир по UX и скорости, но официальный мобильный демо-путь у Apple заточен под Apple devices. Для Android его нельзя считать готовым SDK-ответом. Его стоит рассматривать как направление: меньше visual tokens, быстрее первый токен, меньше лишней работы на кадре.

MediaPipe LLM Inference API уже помечен как устаревающий путь, Google рекомендует смотреть в сторону LiteRT-LM. Поэтому для Android-LLM надо отдельно проверять LiteRT, AI Edge Gallery и доступность AICore/Gemini Nano на целевых устройствах.

Gemini Nano/AICore выглядит интересно для приватного on-device сценария, но его доступность зависит от устройства и системного слоя. Не стоит строить MVP так, будто он гарантированно есть у каждого пользователя.

Bonsai пока не основной кандидат. 1-bit/ternary модели интересны по плотности, но для OSNOVA AI нужна мультимодальность и понятный Android runtime. Bonsai можно держать как исследование для лёгкого текстового слоя, но не как VLM для камеры.

## Источники

- [CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze)
- [ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit Object Detection and Tracking](https://developers.google.cn/ml-kit/vision/object-detection/android)
- [ML Kit Digital Ink Recognition](https://developers.google.com/ml-kit/vision/digital-ink-recognition)
- [LiteRT for Android](https://ai.google.dev/edge/litert/android)
- [MediaPipe LLM Inference for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
- [Gemini Nano on Android](https://developer.android.com/ai/gemini-nano)
- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
- [Apple FastVLM](https://github.com/apple/ml-fastvlm)
