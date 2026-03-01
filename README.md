<div align="center">

# MinimaWallet

**Native Android wallet for the [Minima](https://minima.global) blockchain network**
**Нативный Android-кошелёк для блокчейн-сети [Minima](https://minima.global)**

[![Build APK](https://github.com/serg-83/MinimaWallet/actions/workflows/build.yml/badge.svg)](https://github.com/serg-83/MinimaWallet/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/serg-83/MinimaWallet?color=0D9488)](https://github.com/serg-83/MinimaWallet/releases/latest)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![Language](https://img.shields.io/badge/Java-8-orange?logo=openjdk)](https://openjdk.org)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[**Download APK**](https://github.com/serg-83/MinimaWallet/releases/latest) · [Releases](https://github.com/serg-83/MinimaWallet/releases)

</div>

---

## English

### About

MinimaWallet is a native Android application for managing keys and interacting with the Minima blockchain network. **No node required.** Built with **Material Design 3**, Navigation Drawer navigation, and biometric protection.

### How it works

```
Seed Phrase → [locally] → Private Key + Address
                                  │
            ┌─────────────────────┼─────────────────────┐
            ▼                     ▼                     ▼
     Request balance      Get unsigned transaction   Broadcast signed
    wallet.minima.global   wallet.minima.global      transaction
          (API)                  (API)            wallet.minima.global
                                  │                    (API)
                                  ▼
                       [locally, no internet]
                        Sign transaction with
                           private key
```

> 🔐 **The private key never leaves the device.** Signing happens locally without transmitting the key over the network. Only public data is sent via API: address, balance, unsigned and signed transaction.

### Features

| Section | Description |
|---|---|
| 💰 **Wallet** | View balance, generate cryptographic keys and addresses |
| 📤 **Send** | Send transactions to the Minima network with token selector |
| 🌐 **Explorer** | Built-in blockchain explorer with back/refresh navigation |
| 🔒 **FutureCash** | Lock funds until a future date with time-lock scripts |
| 📈 **Maximize** | Stake MINIMA for 1–12 months and earn guaranteed returns (0.5%–9%) |
| 🔑 **Seed Phrase** | Securely store and manage your seed phrase (BIP39) |
| ⚙️ **Settings** | Configure node URL |
| 🔒 **Biometrics** | Fingerprint authentication on startup |

### Working with API

By default, the app uses the public API endpoint:

```
https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD
```

This UID is permanent and does not change. **If the API URL field is left empty, this default endpoint will be used.**

To use your own API endpoint:

1. Make sure your server node has **Public Wallet** and **MegaMMR** enabled
2. Open the Public Wallet in your browser
3. Copy the full URL including the UID — it should look like:
   ```
   https://YOUR_IP:9003/mdscommand_/cmd?uid=YOUR_UID
   ```
4. Paste it into the Settings → API URL field

> ⚠️ **Note:** The UID will most likely change after a server restart. You will need to update the URL in the app settings.
>
> Example of a working API URL:
> ```
> https://spartacusrex.com:8888/mdscommand_/cmd?uid=0x12D2B61F...79C
> ```

---

### Working with Seed Phrase

On first launch, you can enter a seed phrase for your wallet. A seed phrase can be **any word, symbol, character, or combination of them** — even a space counts as a valid phrase.

- 🎲 You can generate a **random 24-word phrase** using the built-in generator
- 💾 Press **Save Phrase** to store it encrypted with your fingerprint
- 📝 **Write your phrase down on paper!** If you forget or lose it, **nobody can recover it**

To view or change your saved phrase:

1. Go to **Seed Phrase** in the navigation menu
2. Authenticate with your fingerprint
3. Make changes if needed and press **Save** again
4. If no changes — simply navigate to the Wallet and continue using the app

---

### Working with Address

The app works with **one address number at a time**. You can generate any address number associated with your seed phrase.

- Address numbers range from **0** to **999,999,999** — that's how many deterministic addresses the app supports for a single phrase
- 💡 **Tip:** Don't use excessively large address numbers to avoid confusion — keep it simple

---

### Security

- **No node required** — the app works via the public API `wallet.minima.global`
- **Keys on-the-fly** — private key is derived from the seed phrase each time and never stored
- **Offline signing** — transactions are signed locally on the device, the key is never sent over the network
- Seed phrase stored **exclusively** in Android Keystore encrypted storage (AES-256-GCM)
- Key generation using **SecureRandom** (no java.util.Random)
- Biometric authentication via **BiometricPrompt**
- Cleartext traffic blocked via `network_security_config.xml`

### Architecture

```
app/src/main/java/com/example/minimawallet/
├── MainActivity.java            # Main activity, Navigation Drawer
├── WalletFragment.java          # Balance, key generation, token list
├── SendFragment.java            # Send transactions with token selector
├── ExplorerFragment.java        # Built-in blockchain explorer (WebView)
├── FutureCashFragment.java      # FutureCash tabs container
├── FutureSendFragment.java      # Create time-locked transactions
├── FutureCoinsFragment.java     # List/collect locked coins
├── MaximizeFragment.java        # Maximize (staking) tabs container
├── MaximizeStakeFragment.java   # Stake MINIMA for 1-12 months
├── MaximizeBondsFragment.java   # List/cancel active stakes
├── SeedPhraseFragment.java      # Seed phrase management
├── SettingsFragment.java        # Settings
├── LogFragment.java             # Server response log
├── WalletViewModel.java         # Shared state (LiveData)
├── KeyGenerator.java            # Key generation, tx building, staking
└── SecureStorage.java           # Encrypted storage (Android Keystore)
```

**Stack:** Java · Material Design 3 · ViewBinding · ViewModel/LiveData · BiometricPrompt · Android Keystore
Min SDK: **26 (Android 8.0)** · Target SDK: **33 (Android 13)**

### Build

<details>
<summary>Debug (local)</summary>

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
</details>

<details>
<summary>Release (local)</summary>

Create `keystore.properties` in the project root (excluded from git):
```properties
storeFile=release-keystore.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
</details>

<details>
<summary>CI/CD (GitHub Actions)</summary>

Release APK is built automatically on every push to `main`. Version is read from `versionName` in `build.gradle`. Keystore is stored in GitHub Secrets — never in the repository. See [build.yml](.github/workflows/build.yml).
</details>

---

## Русский

### О проекте

MinimaWallet — нативное Android-приложение для управления ключами и взаимодействия с блокчейн-сетью Minima. **Не требует запуска собственной ноды.** Реализован чистый интерфейс в стиле **Material Design 3** с боковым навигационным меню (Navigation Drawer) и биометрической защитой.

### Как это работает

```
Seed-фраза → [локально] → Приватный ключ + Адрес
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
     Запрос баланса       Получение неподписанной    Отправка подписанной
    wallet.minima.global      транзакции              транзакции
         (API)             wallet.minima.global     wallet.minima.global
                                (API)                    (API)
                                    │
                                    ▼
                          [локально, без интернета]
                            Подписание транзакции
                            приватным ключом
```

> 🔐 **Приватный ключ никогда не покидает устройство.** Подписание происходит локально, без передачи ключа в сеть. Через API передаются только публичные данные: адрес, баланс, неподписанная и подписанная транзакция.

### Возможности

| Раздел | Описание |
|---|---|
| 💰 **Кошелёк** | Просмотр баланса, генерация криптографических ключей и адресов |
| 📤 **Отправка** | Отправка транзакций с выбором токена |
| 🌐 **Эксплорер** | Встроенный обозреватель блокчейна с навигацией назад/обновить |
| 🔒 **FutureCash** | Блокировка средств до будущей даты через time-lock скрипты |
| 📈 **Maximize** | Стейкинг MINIMA на 1–12 месяцев с гарантированным доходом (0.5%–9%) |
| 🔑 **Seed-фраза** | Безопасное хранение и управление сид-фразой (BIP39) |
| ⚙️ **Настройки** | Настройка URL узла |
| 🔒 **Биометрия** | Аутентификация по отпечатку пальца при запуске |

### Работа с API

По умолчанию приложение использует публичный API-адрес:

```
https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD
```

Этот UID постоянный и не меняется. **Если поле API URL оставить пустым, будет использоваться именно этот адрес.**

Чтобы использовать собственный API-адрес:

1. Убедитесь, что на вашем сервере в ноде включены **Public Wallet** и **MegaMMR**
2. Откройте Public Wallet в браузере
3. Скопируйте полный адрес вместе с UID — он будет вида:
   ```
   https://ВАШ_IP:9003/mdscommand_/cmd?uid=ВАШ_UID
   ```
4. Вставьте его в настройках приложения → API URL

> ⚠️ **Важно:** После перезагрузки сервера UID скорее всего изменится. Вам нужно будет обновить URL в настройках приложения.
>
> Пример рабочего API-адреса:
> ```
> https://spartacusrex.com:8888/mdscommand_/cmd?uid=0x12D2B61F...79C
> ```

---

### Работа с фразой

При первом запуске приложения вам доступен ввод фразы для вашего кошелька. Фразой может быть **любое слово, символ, знак или их сочетание** — даже пробел является допустимой фразой.

- 🎲 Вы можете сгенерировать **случайную фразу из 24 слов** встроенным генератором
- 💾 Нажмите **Сохранить фразу** — она сохранится зашифрованной отпечатком пальца
- 📝 **Обязательно запишите фразу на бумаге!** Если вы её забудете или потеряете — **никто не сможет её восстановить**

Чтобы посмотреть или изменить сохранённую фразу:

1. Перейдите в пункт меню **Seed-фраза**
2. Отсканируйте отпечаток пальца
3. При необходимости внесите изменения и нажмите **Сохранить** повторно
4. Если изменений нет — просто перейдите в кошелёк и продолжайте использовать приложение

---

### Работа с адресом

Программа в один момент времени работает только с **одним номером адреса**. Вы можете сгенерировать любой номер адреса, относящийся к вашей фразе.

- Номер адреса можно выбрать от **0** до **999 999 999** — именно столько детерминированных адресов поддерживает программа для одной фразы
- 💡 **Совет:** Не старайтесь использовать слишком большие номера адресов, чтобы самому не запутаться — выбирайте простые числа

---

### Безопасность

- **Нода не нужна** — приложение работает через публичный API `wallet.minima.global`
- **Ключи на лету** — приватный ключ генерируется из seed-фразы каждый раз заново и нигде не сохраняется
- **Подписание офлайн** — транзакция подписывается локально на устройстве без передачи ключа в сеть
- Сид-фраза хранится **только** в зашифрованном хранилище Android Keystore (AES-256-GCM)
- Генерация ключей на базе **SecureRandom** (без java.util.Random)
- Биометрическая защита через **BiometricPrompt**
- Запрещён cleartext-трафик (`network_security_config.xml`)

### Установка

Скачайте APK из [Releases](https://github.com/serg-83/MinimaWallet/releases/latest) и установите на Android-устройство.

> ⚠️ Разрешите установку из неизвестных источников в настройках Android.

---

## Support the Author / Поддержать автора

| Network | Address |
|---|---|
| **Minima** | `MxG083R69BFAHDGPDMURJTYJBRM0E83MGP8U0C5DNJ6FCJHF40M5AF4YEN9ZVSH` |
| **EVM (ETH и др.)** | `0x966760456ceB665FB010720f320b199F9d3D6db3` |

---

## License / Лицензия

[MIT License](LICENSE)
