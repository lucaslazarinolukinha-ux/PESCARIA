package br.com.pesca.main;

import br.com.pesca.model.Partida;
import br.com.pesca.model.Pescador;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Servidor web do jogo de pesca (2 jogadores).
 *
 * - Serve a pasta "web" (index.html, style.css, duelo.css, script.js)
 * - API: /api/entrar, /api/sala/entrar, /api/sala/sair, /api/estado,
 *        /api/pescar, /api/descansar, /api/msg, /api/ranking
 * - Cada partida (Partida.java) é a memória compartilhada dos 2 jogadores.
 * - Salva o progresso dos jogadores em arquivo (dados/jogadores.txt)
 *
 * Variáveis de ambiente (opcionais):
 *   PORT      -> porta do servidor (padrão 8080).
 *   DATA_DIR  -> pasta onde o progresso é salvo (padrão "dados").
 */
public class ServidorWeb {

    private static final Path ARQUIVO =
            Path.of(System.getenv().getOrDefault("DATA_DIR", "dados"), "jogadores.txt");

    // jogadores por nome (minúsculo) e sessões por token
    private static final Map<String, Pescador> jogadores = new ConcurrentHashMap<>();
    private static final Map<String, Pescador> sessoes = new ConcurrentHashMap<>();

    // salas: lista de partidas + em qual partida cada jogador está
    private static final Object lockSalas = new Object();
    private static final List<Partida> partidas = new ArrayList<>();
    private static final Map<String, Partida> salaDe = new ConcurrentHashMap<>();
    private static long proximaPartida = 1;

    public static void main(String[] args) throws IOException {
        int porta = 8080;
        String portaEnv = System.getenv("PORT");
        if (portaEnv != null && !portaEnv.isBlank()) {
            porta = Integer.parseInt(portaEnv.trim());
        }

        // Procura a pasta "web" (abrindo o VS Code na pasta pesca ou na pasta acima)
        Path pastaWeb = Path.of("web");
        if (!Files.isDirectory(pastaWeb)) {
            pastaWeb = Path.of("pesca", "web");
        }
        final Path raiz = pastaWeb.toAbsolutePath().normalize();

        carregarDados();

        HttpServer server = HttpServer.create(new InetSocketAddress(porta), 0);

        // uma thread por requisição: os 2 jogadores acessam a Partida ao mesmo tempo
        server.setExecutor(Executors.newCachedThreadPool());

        for (String acao : new String[]{"entrar", "sala/entrar", "sala/sair", "estado", "pescar", "descansar", "msg"}) {
            server.createContext("/api/" + acao, ex -> api(ex, acao));
        }
        server.createContext("/api/ranking", ServidorWeb::ranking);
        server.createContext("/", ex -> arquivo(ex, raiz));

        server.start();
        System.out.println("Jogo de pesca no ar: http://localhost:" + porta);
        System.out.println("Pasta web: " + raiz);
        System.out.println("Progresso salvo em: " + ARQUIVO.toAbsolutePath());
    }

    // ---------- API ----------

    private static void api(HttpExchange ex, String acao) throws IOException {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());

            // ----- login -----
            if (acao.equals("entrar")) {
                String nome = limparNome(q.getOrDefault("nome", ""));
                if (nome.isEmpty()) {
                    enviar(ex, 400, "application/json", "{\"erro\":\"Digite um nome (letras e números)\"}");
                    return;
                }

                String chave = nome.toLowerCase(Locale.ROOT);
                boolean novo = !jogadores.containsKey(chave);
                Pescador p = jogadores.computeIfAbsent(chave, k -> new Pescador(nome));

                if (sessoes.size() > 20000) sessoes.clear(); // proteção contra encher a memória
                String token = UUID.randomUUID().toString();
                sessoes.put(token, p);
                if (novo) salvar();

                String estado;
                synchronized (p) { estado = estado(p); }
                enviar(ex, 200, "application/json", "{\"token\":\"" + token + "\"," + estado + "}");
                return;
            }

            // ----- daqui para baixo precisa estar logado -----
            Pescador p = sessoes.get(q.getOrDefault("token", ""));
            if (p == null) {
                enviar(ex, 401, "application/json", "{\"erro\":\"Sessão inválida. Entre de novo.\"}");
                return;
            }
            String chave = p.getNome().toLowerCase(Locale.ROOT);

            if (acao.equals("sala/entrar")) {
                Partida nova = entrarNaSala(p, chave);
                String json = nova.estadoJson(nova.buscar(p), 0);
                if (nova.pegarSalvarPendente()) salvar();
                enviar(ex, 200, "application/json", json);
                return;
            }

            if (acao.equals("sala/sair")) {
                Partida saindo = salaDe.remove(chave);
                if (saindo != null) {
                    Partida.Participante quem = saindo.buscar(p);
                    if (quem != null) saindo.sair(quem);
                    if (saindo.pegarSalvarPendente()) salvar();
                }
                enviar(ex, 200, "application/json", "{\"ok\":true}");
                return;
            }

            Partida partida = salaDe.get(chave);
            Partida.Participante eu = partida == null ? null : partida.buscar(p);
            if (eu == null) {
                enviar(ex, 409, "application/json", "{\"erro\":\"Você não está em uma partida.\"}");
                return;
            }

            String json;
            switch (acao) {
                case "estado": {
                    json = partida.estadoJson(eu, lerLong(q.get("desde")));
                    break;
                }
                case "pescar": {
                    Partida.Jogada j = partida.pescar(eu, lerInt(q.get("forca"), 10));
                    if (j.erro != null) {
                        enviar(ex, j.status, "application/json",
                                "{\"erro\":\"" + esc(j.erro) + "\"," + dadosJogada(j) + "}");
                        return;
                    }
                    json = "{" + dadosJogada(j) + ",\"forca\":" + j.forca + ",\"peixe\":{"
                         + "\"nome\":\"" + esc(j.peixe.getNome()) + "\","
                         + "\"raridade\":\"" + j.peixe.getRaridade() + "\","
                         + "\"valor\":" + j.peixe.getValor() + "}}";
                    break;
                }
                case "descansar": {
                    Partida.Jogada j = partida.descansar(eu);
                    if (j.erro != null) {
                        enviar(ex, j.status, "application/json",
                                "{\"erro\":\"" + esc(j.erro) + "\"," + dadosJogada(j) + "}");
                        return;
                    }
                    json = "{" + dadosJogada(j) + "}";
                    break;
                }
                case "msg": {
                    partida.enviarChat(eu, q.getOrDefault("texto", ""));
                    json = "{\"ok\":true}";
                    break;
                }
                default:
                    enviar(ex, 404, "application/json", "{\"erro\":\"Ação desconhecida\"}");
                    return;
            }

            if (partida.pegarSalvarPendente()) salvar();
            enviar(ex, 200, "application/json", json);

        } catch (Exception e) {
            e.printStackTrace();
            enviar(ex, 500, "application/json", "{\"erro\":\"Erro interno\"}");
        }
    }

    /** Coloca o jogador numa partida: reconecta, entra numa que espera ou cria uma nova. */
    private static Partida entrarNaSala(Pescador p, String chave) {
        synchronized (lockSalas) {
            // descarta partidas abandonadas
            partidas.removeIf(x -> {
                if (!x.obsoleta()) return false;
                salaDe.values().removeIf(v -> v == x);
                return true;
            });

            Partida atual = salaDe.get(chave);
            if (atual != null) {
                Partida.Participante eu = atual.buscar(p);
                if (eu != null && !atual.isTerminada()) return atual; // reconectou
                salaDe.remove(chave);
            }

            Partida escolhida = null;
            for (Partida c : partidas) {
                if (c.temVaga() && c.buscar(p) == null) {
                    escolhida = c;
                    break;
                }
            }
            if (escolhida == null) {
                escolhida = new Partida(proximaPartida++);
                partidas.add(escolhida);
            }
            escolhida.adicionar(p);
            salaDe.put(chave, escolhida);
            return escolhida;
        }
    }

    private static String dadosJogada(Partida.Jogada j) {
        return "\"energia\":" + j.energia + ",\"cansado\":" + j.cansado + ",\"moedas\":" + j.moedas;
    }

    /** Top 10 pescadores por moedas (perfil salvo, todas as partidas). */
    private static void ranking(HttpExchange ex) throws IOException {
        try {
            List<Pescador> lista = new ArrayList<>(jogadores.values());
            lista.sort((a, b) -> {
                int c = Integer.compare(b.getMoedas(), a.getMoedas());
                return c != 0 ? c : Integer.compare(b.getNivel(), a.getNivel());
            });

            StringBuilder sb = new StringBuilder("[");
            int total = Math.min(10, lista.size());
            for (int i = 0; i < total; i++) {
                Pescador p = lista.get(i);
                if (i > 0) sb.append(",");
                synchronized (p) { sb.append("{").append(estado(p)).append("}"); }
            }
            sb.append("]");

            ex.getResponseHeaders().set("Cache-Control", "no-store");
            enviar(ex, 200, "application/json", sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            enviar(ex, 500, "application/json", "[]");
        }
    }

    private static String estado(Pescador p) {
        return "\"nome\":\"" + esc(p.getNome()) + "\","
             + "\"nivel\":" + p.getNivel() + ","
             + "\"moedas\":" + p.getMoedas();
    }

    /** Só letras, números, espaço, _ . - (máx. 20). Evita nomes estranhos e HTML. */
    private static String limparNome(String bruto) {
        String s = bruto.replaceAll("[^\\p{L}\\p{N} _.-]", "").trim();
        if (s.length() > 20) s = s.substring(0, 20).trim();
        return s;
    }

    // ---------- Persistência (arquivo simples: nome;nivel;moedas) ----------

    private static synchronized void salvar() {
        try {
            Path pasta = ARQUIVO.toAbsolutePath().getParent();
            if (pasta != null) Files.createDirectories(pasta);

            List<String> linhas = new ArrayList<>();
            for (Pescador p : jogadores.values()) {
                synchronized (p) {
                    linhas.add(p.getNome() + ";" + p.getNivel() + ";" + p.getMoedas());
                }
            }
            Files.write(ARQUIVO, linhas, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Falha ao salvar progresso: " + e.getMessage());
        }
    }

    private static void carregarDados() {
        if (!Files.isRegularFile(ARQUIVO)) return;
        try {
            for (String linha : Files.readAllLines(ARQUIVO, StandardCharsets.UTF_8)) {
                String[] partes = linha.split(";");
                if (partes.length != 3) continue;
                try {
                    String nome = partes[0];
                    int nivel = Integer.parseInt(partes[1].trim());
                    int moedas = Integer.parseInt(partes[2].trim());
                    jogadores.put(nome.toLowerCase(Locale.ROOT), restaurar(nome, nivel, moedas));
                } catch (NumberFormatException ignorada) { }
            }
            System.out.println("Jogadores carregados: " + jogadores.size());
        } catch (IOException e) {
            System.out.println("Falha ao ler progresso: " + e.getMessage());
        }
    }

    /** Recria um Pescador com o nível e as moedas salvos. */
    private static Pescador restaurar(String nome, int nivel, int moedas) {
        Pescador p = new Pescador(nome);
        int falta = moedas - p.getMoedas();
        if (falta > 0) p.adicionarMoedas(falta);
        for (int i = 0; i < 100000 && p.getNivel() < nivel; i++) {
            p.subirNivel();
        }
        return p;
    }

    // ---------- Arquivos estáticos ----------

    private static void arquivo(HttpExchange ex, Path raiz) throws IOException {
        String caminho = ex.getRequestURI().getPath();
        if (caminho.equals("/")) caminho = "/index.html";

        Path arq = raiz.resolve(caminho.substring(1)).normalize();

        // impede acessar arquivos fora da pasta web
        if (!arq.startsWith(raiz) || !Files.isRegularFile(arq)) {
            enviar(ex, 404, "text/plain; charset=utf-8", "Não encontrado");
            return;
        }

        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        enviarBytes(ex, 200, tipo(arq.toString()), Files.readAllBytes(arq));
    }

    private static String tipo(String nome) {
        if (nome.endsWith(".html")) return "text/html; charset=utf-8";
        if (nome.endsWith(".css")) return "text/css; charset=utf-8";
        if (nome.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (nome.endsWith(".png")) return "image/png";
        if (nome.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ---------- Utilitários ----------

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k, v);
        }
        return m;
    }

    private static int lerInt(String s, int padrao) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return padrao;
        }
    }

    private static long lerLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void enviar(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        enviarBytes(ex, status, tipo.contains("charset") ? tipo : tipo + "; charset=utf-8",
                corpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void enviarBytes(HttpExchange ex, int status, String tipo, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}