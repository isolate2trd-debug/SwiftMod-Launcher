package net.kdt.pojavlaunch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SwiftModVoiceAssistantActivity extends Activity {
    private static final int REQ_AUDIO = 4001;
    private static final String PREFS = "swiftmod_ai";
    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String MODEL = "gpt-6-luna";

    private TextView status;
    private TextView transcript;
    private EditText apiKey;
    private Button micButton;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_swiftmod_voice_assistant);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        status = findViewById(R.id.ai_status);
        transcript = findViewById(R.id.ai_transcript);
        apiKey = findViewById(R.id.ai_api_key);
        micButton = findViewById(R.id.ai_mic);

        apiKey.setText(prefs.getString("api_key", ""));
        findViewById(R.id.ai_save).setOnClickListener(v -> {
            prefs.edit().putString("api_key", apiKey.getText().toString().trim()).apply();
            Toast.makeText(this, "AI key saved on this device.", Toast.LENGTH_SHORT).show();
        });

        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.0f);
            }
        });

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Speech recognition is not available on this phone.");
            micButton.setEnabled(false);
        } else {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { status.setText("Listening…"); }
                @Override public void onBeginningOfSpeech() { status.setText("Listening…"); }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { status.setText("Thinking…"); }
                @Override public void onError(int error) {
                    status.setText("Tap the mic and try again.");
                    micButton.setEnabled(true);
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    micButton.setEnabled(true);
                    if (matches == null || matches.isEmpty()) {
                        status.setText("I didn't catch that.");
                        return;
                    }
                    sendToAI(matches.get(0));
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }

        micButton.setOnClickListener(v -> startListening());
        findViewById(R.id.ai_close).setOnClickListener(v -> finish());
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (recognizer == null) return;
        String key = apiKey.getText().toString().trim();
        if (key.isEmpty()) {
            status.setText("Save your AI API key first.");
            apiKey.requestFocus();
            return;
        }

        micButton.setEnabled(false);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(intent);
    }

    private void sendToAI(String userText) {
        runOnUiThread(() -> {
            transcript.setText("You: " + userText);
            status.setText("Thinking…");
        });

        final String key = apiKey.getText().toString().trim();
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", MODEL);
                body.put("instructions",
                        "You are SwiftMod AI, a concise friendly Minecraft launcher companion. " +
                        "Help with Minecraft Java, mods, resource packs, shaders, launcher settings, " +
                        "and general questions. Do not claim to control the game unless a supported action exists.");
                JSONArray input = new JSONArray();
                JSONObject message = new JSONObject();
                message.put("role", "user");
                message.put("content", userText);
                input.put(message);
                body.put("input", input);

                HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Content-Type", "application/json");

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                        StandardCharsets.UTF_8));
                StringBuilder responseText = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) responseText.append(line);
                reader.close();

                if (code < 200 || code >= 300) {
                    JSONObject error = new JSONObject(responseText.toString());
                    String messageText = error.optJSONObject("error") != null
                            ? error.optJSONObject("error").optString("message", "AI request failed.")
                            : "AI request failed (" + code + ").";
                    throw new Exception(messageText);
                }

                JSONObject response = new JSONObject(responseText.toString());
                String answer = response.optString("output_text", "");
                if (answer.isEmpty()) {
                    JSONArray output = response.optJSONArray("output");
                    if (output != null) {
                        for (int i = 0; i < output.length() && answer.isEmpty(); i++) {
                            JSONObject item = output.optJSONObject(i);
                            JSONArray content = item == null ? null : item.optJSONArray("content");
                            if (content == null) continue;
                            for (int j = 0; j < content.length(); j++) {
                                JSONObject part = content.optJSONObject(j);
                                if (part != null && "output_text".equals(part.optString("type"))) {
                                    answer = part.optString("text", "");
                                    if (!answer.isEmpty()) break;
                                }
                            }
                        }
                    }
                }
                if (answer.isEmpty()) answer = "I received your message, but no text response was returned.";

                final String finalAnswer = answer;
                runOnUiThread(() -> {
                    transcript.setText("You: " + userText + "\n\nSwiftMod AI: " + finalAnswer);
                    status.setText("Tap the mic to talk again.");
                    tts.speak(finalAnswer, TextToSpeech.QUEUE_FLUSH, null, "swiftmod_ai_reply");
                });
            } catch (Exception e) {
                final String error = e.getMessage() == null ? "Connection failed." : e.getMessage();
                runOnUiThread(() -> {
                    status.setText("AI connection failed.");
                    transcript.setText("You: " + userText + "\n\n" + error);
                    micButton.setEnabled(true);
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else if (requestCode == REQ_AUDIO) {
            status.setText("Microphone permission is needed for voice chat.");
            micButton.setEnabled(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        executor.shutdownNow();
        super.onDestroy();
    }
}
