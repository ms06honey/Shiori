# BrainBox 📚

AI を活用したブックマーク管理 Android アプリです。  
URL を保存するだけで Gemini AI が自動でページを解析し、要約・カテゴリ・タグを付与します。

---

## 主な機能

| 機能 | 説明 |
|------|------|
| ブックマーク保存 | URL を登録するとバックグラウンドで自動解析 |
| AI サマリー | Gemini API によるページ内容の日本語要約 |
| 自動カテゴリ分類 | AI がカテゴリ・タグを自動付与 |
| 再解析 | 詳細画面からいつでも再解析を実行 |
| 暗号化ストレージ | Room + SQLCipher でデータを端末内に安全保存 |
| ブラウザ連携 | 詳細画面からそのまま外部ブラウザで開ける |

---

## 技術スタック

- **言語**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **DI**: Hilt 2.59
- **DB**: Room 2.7 + SQLCipher 4.5
- **AI**: Google Generative AI (Gemini) 0.9
- **非同期**: Kotlin Coroutines + WorkManager
- **スクレイピング**: Jsoup + OkHttp
- **ナビゲーション**: Navigation Compose

---

## 事前準備

### 必須要件

| ツール | バージョン |
|--------|-----------|
| Android Studio | Ladybug (2024.2) 以降推奨 |
| JDK | 11 以上 |
| Android SDK | API 26 (minSdk) 〜 API 36 (targetSdk) |
| Gemini API キー | [Google AI Studio](https://aistudio.google.com/) で取得 |

### 1. リポジトリをクローン

```bash
git clone https://github.com/<your-account>/BrainBox.git
cd BrainBox
```

### 2. Gemini API キーを設定

プロジェクトルートの `local.properties` に以下を追記します。

```properties
GEMINI_API_KEY=<取得した API キー>
```

> **注意**: `local.properties` は `.gitignore` に含めてください。APIキーをリポジトリにコミットしないよう注意してください。

### 3. 依存関係の取得

Android Studio を開くと Gradle が自動同期します。  
コマンドラインで行う場合:

```bash
./gradlew build
```

---

## ビルドバリアント

| バリアント | 説明 |
|-----------|------|
| `debug` | 開発用。署名不要でそのままインストール可能 |
| `release` | ProGuard による難読化・最適化あり。署名キーストアが必要 |

---

## デバイスへのデプロイ方法

### 方法 1: Android Studio から実行（推奨）

1. Android Studio でプロジェクトを開く
2. USB ケーブルまたは Wi-Fi でデバイスを接続する
3. デバイス側で **開発者オプション → USB デバッグ** を有効にする
4. ツールバーのデバイス選択ドロップダウンからターゲットを選択する  
   （実機 / エミュレーター どちらも可）
5. ▶ **Run 'app'** ボタン（Shift + F10）をクリック

> Wi-Fi 経由でペアリングする場合は  
> Android Studio → **Device Manager → Pair via Wi-Fi** から QR コードでペアリングできます。

---

### 方法 2: Gradle コマンドで実機インストール

デバイスを USB 接続した状態で以下を実行します。

```bash
# debug ビルドをインストール
./gradlew installDebug

# インストール済みの APK を起動
adb shell am start -n com.example.brainbox/.MainActivity
```

複数デバイスが接続されている場合は `-s` でシリアルを指定します。

```bash
adb devices                          # シリアル番号を確認
adb -s <シリアル番号> install app/build/outputs/apk/debug/app-debug.apk
```

---

### 方法 3: APK ファイルを手動インストール

```bash
# debug APK をビルド
./gradlew assembleDebug
```

生成された APK は以下のパスに出力されます。

```
app/build/outputs/apk/debug/app-debug.apk
```

この APK ファイルをデバイスに転送（メール・ファイル共有など）し、デバイス上で開いてインストールしてください。  
事前にデバイスの **設定 → セキュリティ → 提供元不明のアプリ** のインストールを許可する必要があります。

---

### 方法 4: エミュレーターで実行

```bash
# 利用可能な AVD を一覧表示
emulator -list-avds

# 特定の AVD を起動（Android Studio の AVD Manager でも可）
emulator -avd <AVD 名>

# 起動後にインストール
./gradlew installDebug
```

---

## CI/CD 環境向け設定

GitHub Actions などのCI環境では、`local.properties` の代わりに環境変数を使用します。

```yaml
# .github/workflows/build.yml (例)
env:
  GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
```

`build.gradle.kts` はすでに環境変数 `GEMINI_API_KEY` へのフォールバックを実装済みです。

---

## ライセンス

MIT License

