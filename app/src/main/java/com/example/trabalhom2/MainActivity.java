package com.example.trabalhom2;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private LinearLayout linearLayoutCursos;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        linearLayoutCursos = findViewById(R.id.linearLayoutCursos);
        dbHelper = new DatabaseHelper(this);

        fetchJsonData();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
        return false;
    }

    private void fetchJsonData() {
        if (isNetworkAvailable()) {
            Thread thread = new Thread(() -> {
                HttpURLConnection con = null;
                try {
                    String resourceURI = "https://raw.githubusercontent.com/rafaelpm0/data_mobile_2/refs/heads/main/data.json";
                    URL url = new URL(resourceURI);
                    con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("GET");
                    // Desativar cache para garantir que o JSON mais recente seja baixado
                    con.setUseCaches(false);
                    con.setRequestProperty("Cache-Control", "no-cache");
                    con.setConnectTimeout(10000); // Aumentar timeout para 10 segundos
                    con.setReadTimeout(10000);

                    int responseCode = con.getResponseCode();
                    Log.d("DEBUG_JSON", "Código de resposta HTTP: " + responseCode);
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new Exception("Erro HTTP: " + responseCode);
                    }

                    InputStream is = con.getInputStream();
                    String response = convertStreamToString(is);
                    Log.d("DEBUG_JSON", "JSON baixado: " + response);

                    runOnUiThread(() -> parseJson(response));
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("DEBUG_ERROR", "Erro ao baixar o JSON: " + e.getMessage());
                    runOnUiThread(() -> {
                        displayItems();
                        // Exibir mensagem ao usuário
                        Toast.makeText(MainActivity.this, "Falha ao atualizar dados. Exibindo dados locais.", Toast.LENGTH_LONG).show();
                    });
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            });
            thread.start();
        } else {
            Log.d("DEBUG_OFFLINE", "Sem conexão com a internet, exibindo dados locais");
            runOnUiThread(() -> {
                displayItems();
                // Exibir mensagem ao usuário
                Toast.makeText(MainActivity.this, "Sem conexão. Exibindo dados locais.", Toast.LENGTH_LONG).show();
            });
        }
    }


    private String convertStreamToString(InputStream is) {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        } catch (Exception e) {
            return "Erro ao ler o fluxo de dados";
        }
        return response.toString();
    }

    private void parseJson(String json) {
        new Thread(() -> {
            try {
                Gson gson = new Gson();
                Type listType = new TypeToken<List<Prato>>(){}.getType();
                List<Prato> listaPratos = gson.fromJson(json, listType);

                // Limpar o banco antes de inserir novos dados
                dbHelper.clearTable(); // Movido para cá
                for (Prato prato : listaPratos) {
                    String imagePath = downloadAndSaveImage(prato.getImagem(), prato.getNome());
                    dbHelper.insertItem(prato.getNome(), prato.getPreco(), prato.getDescricao(), imagePath);
                    Log.d("DEBUG_SQLITE", "Salvando: " + prato.getNome() + " - " + imagePath);
                }

                runOnUiThread(() -> displayItems());
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("DEBUG_ERROR", "Erro ao processar JSON: " + e.getMessage());
                runOnUiThread(() -> displayItems());
            }
        }).start();
    }


    private String downloadAndSaveImage(String imageUrl, String imageName) {
        File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (picturesDir == null) return "";

        File imageFile = new File(picturesDir, imageName + ".png");

        try {
            // Validar URL
            if (imageUrl == null || imageUrl.isEmpty()) {
                Log.e("DEBUG_IMAGE", "URL da imagem vazia para: " + imageName);
                return imageFile.exists() ? imageFile.getAbsolutePath() : "";
            }

            // Montar URL completa
            String fullImageUrl = "https://raw.githubusercontent.com/rafaelpm0/data_mobile_2/main/" + imageUrl;
            Log.d("DEBUG_IMAGE", "Tentando baixar de: " + fullImageUrl);

            // Abrir conexão
            URL url = new URL(fullImageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoInput(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DEBUG_IMAGE", "Erro HTTP " + responseCode + " ao baixar imagem: " + imageName);
                connection.disconnect();
                // Fallback: usar local se existir
                return imageFile.exists() ? imageFile.getAbsolutePath() : "";
            }

            // Salvar imagem
            try (InputStream inputStream = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream outputStream = new FileOutputStream(imageFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            // Verificar imagem válida
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            if (bitmap == null) {
                Log.e("DEBUG_IMAGE", "Imagem inválida. Excluindo arquivo.");
                if (imageFile.exists()) imageFile.delete();
                return "";
            }

            connection.disconnect();
            return imageFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e("DEBUG_IMAGE", "Erro ao baixar imagem " + imageName + ": " + e.getMessage());
            e.printStackTrace();
            // Fallback: usar local se existir
            return imageFile.exists() ? imageFile.getAbsolutePath() : "";
        }
    }



    private void displayItems() {
        linearLayoutCursos.removeAllViews();
        Cursor cursor = dbHelper.getAllItems();
        Log.d("DEBUG_DISPLAY", "Total de itens: " + cursor.getCount());
        boolean isOffline = !isNetworkAvailable(); // Verificar se está offline

        while (cursor.moveToNext()) {
            String nome = cursor.getString(1);
            double preco = cursor.getDouble(2);
            String descricao = cursor.getString(3);
            String imagePath = cursor.getString(4);
            Log.d("DEBUG_DISPLAY", "Exibindo: " + nome);

            // Container do prato
            LinearLayout pratoLayout = new LinearLayout(this);
            pratoLayout.setOrientation(LinearLayout.VERTICAL);
            pratoLayout.setPadding(16, 16, 16, 16);
            pratoLayout.setBackgroundColor(getResources().getColor(android.R.color.white));
            pratoLayout.setElevation(4);
            pratoLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            // Imagem do prato
            if (!imagePath.isEmpty()) {
                ImageView imageView = new ImageView(this);
                Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            400
                    ));
                    imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    pratoLayout.addView(imageView);
                } else {
                    Log.e("DEBUG_DISPLAY", "Falha ao carregar imagem: " + imagePath);
                }
            } else {
                Log.d("DEBUG_DISPLAY", "Caminho da imagem vazio para: " + nome);
            }

            // Nome do prato
            TextView textViewNome = new TextView(this);
            textViewNome.setText(nome);
            textViewNome.setTextSize(20);
            textViewNome.setTextColor(getResources().getColor(android.R.color.black));
            textViewNome.setPadding(0, 8, 0, 4);
            pratoLayout.addView(textViewNome);

            // Descrição do prato
            TextView textViewDescricao = new TextView(this);
            textViewDescricao.setText(descricao);
            textViewDescricao.setTextSize(16);
            textViewDescricao.setTextColor(getResources().getColor(android.R.color.darker_gray));
            pratoLayout.addView(textViewDescricao);

            // Preço do prato ou "a consultar"
            TextView textViewPreco = new TextView(this);
            if (isOffline) {
                textViewPreco.setText("a consultar");
            } else {
                textViewPreco.setText(String.format("R$ %.2f", preco));
            }
            textViewPreco.setTextSize(18);
            textViewPreco.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            textViewPreco.setPadding(0, 8, 0, 8);
            pratoLayout.addView(textViewPreco);

            // Espaçamento entre os itens
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    8
            );

            View divider = new View(this);
            divider.setLayoutParams(layoutParams);
            divider.setBackgroundColor(getResources().getColor(android.R.color.transparent));

            // Adicionando o layout do prato ao container principal
            linearLayoutCursos.addView(pratoLayout);
            linearLayoutCursos.addView(divider);
        }
        cursor.close();
    }



}
