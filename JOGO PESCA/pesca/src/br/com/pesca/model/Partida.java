package br.com.pesca.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Uma partida para 2 jogadores.
 *
 * MEMÓRIA COMPARTILHADA: o lago (estoque de peixes), o placar e o relógio ficam
 * neste único objeto, na memória do servidor, usado pelos dois jogadores ao mesmo
 * tempo. Todo acesso passa pelo mesmo ReentrantLock (exclusão mútua), para que
 * dois jogadores nunca peguem o mesmo peixe.
 *
 * TROCA DE MENSAGENS: cada jogador tem uma caixa de mensagens (chat + eventos).
 * As mensagens são entregues às duas caixas e o jogador lê só o que é novo.
 */
public class Partida {

    public enum Estado { ESPERANDO, JOGANDO, TERMINADA }

    // ---------- Regras ----------
    public static final long DURACAO_MS = 120_000;       // 2 minutos
    public static final long AUSENTE_MS = 20_000;        // sem contato = saiu
    public static final long INTERVALO_PESCA_MS = 1_000;
    public static final long PESCA_MS = 2_000;
    public static final long DESCANSO_MS = 1_500;
    public static final int BONUS_VITORIA = 100;
    private static final int MAX_CAIXA = 200;

    public static final Peixe[] CATALOGO = {
        new Peixe("Tilápia", Raridade.COMUM, 20),
        new Peixe("Carpa", Raridade.INCOMUM, 40),
        new Peixe("Salmão", Raridade.RARO, 80),
        new Peixe("Tubarão", Raridade.EPICO, 150),
        new Peixe("Megalodon", Raridade.LENDARIO, 400)
    };

    // quantos peixes de cada tipo existem no lago (compartilhado)
    private static final int[] ESTOQUE_INICIAL = {20, 10, 6, 3, 1};

    // chances (%) de cada peixe conforme a força: curto, médio, longo
    private static final int[][] PESOS = {
        {60, 28,  9,  2,  1},
        {30, 30, 25, 12,  3},
        { 5, 15, 30, 32, 18}
    };

    // ---------- Tipos auxiliares ----------

    public static class Mensagem {
        final long id;
        final String tipo;   // chat | evento | raro | sistema
        final String autor;
        final String texto;

        Mensagem(long id, String tipo, String autor, String texto) {
            this.id = id;
            this.tipo = tipo;
            this.autor = autor;
            this.texto = texto;
        }
    }

    public static class Participante {
        final Pescador perfil;   // perfil salvo (nível, moedas, ranking)
        final String nome;
        int moedas = 0;          // moedas só desta partida
        int energia = Pescador.ENERGIA_MAX;
        final int[] peixes = new int[CATALOGO.length];
        long ocupadoAte = 0;
        String acao = "livre";   // pescando | descansando | livre
        long ultimaPesca = 0;
        long ultimaMsg = 0;
        long ultimoContato = System.currentTimeMillis();
        final List<Mensagem> caixa = new ArrayList<>();

        Participante(Pescador perfil) {
            this.perfil = perfil;
            this.nome = perfil.getNome();
        }

        boolean cansado() {
            return energia < Pescador.LIMITE_CANSACO;
        }

        int limitarForca(int forca) {
            int max = cansado() ? Pescador.FORCA_MAX_CANSADO : 100;
            return Math.max(10, Math.min(forca, max));
        }
    }

    /** Resultado de uma jogada (pescar ou descansar). */
    public static class Jogada {
        public String erro;       // null = deu certo
        public int status = 200;  // 409 = recusada, 429 = muito rápido
        public Peixe peixe;
        public int forca;
        public int energia;
        public boolean cansado;
        public int moedas;
    }

    // ---------- Estado compartilhado ----------

    private final long id;
    private final ReentrantLock lock = new ReentrantLock();
    private final Random random = new Random();
    private final List<Participante> participantes = new ArrayList<>();
    private final int[] estoque = ESTOQUE_INICIAL.clone();

    private Estado estado = Estado.ESPERANDO;
    private long fimPrevisto;
    private long terminouEm;
    private String motivo = "";
    private Participante vencedor;        // null = empate
    private long seqMensagem = 0;
    private boolean salvarPendente = false;

    public Partida(long id) {
        this.id = id;
    }

    private static long agora() {
        return System.currentTimeMillis();
    }

    // ---------- Entrar / sair ----------

    public Participante adicionar(Pescador perfil) {
        lock.lock();
        try {
            Participante existente = buscarInterno(perfil);
            if (existente != null) return existente;
            if (estado != Estado.ESPERANDO || participantes.size() >= 2) return null;

            Participante novo = new Participante(perfil);
            participantes.add(novo);

            if (participantes.size() == 2) {
                estado = Estado.JOGANDO;
                fimPrevisto = agora() + DURACAO_MS;
                difundir("sistema", "", "Partida começou! " + participantes.get(0).nome + " x "
                        + participantes.get(1).nome + ". Vence quem juntar mais moedas em 2 minutos!");
            }
            return novo;
        } finally {
            lock.unlock();
        }
    }

    public void sair(Participante p) {
        lock.lock();
        try {
            if (estado == Estado.ESPERANDO) {
                participantes.remove(p);
            } else if (estado == Estado.JOGANDO) {
                finalizar(p.nome + " saiu da partida.", outro(p));
            }
        } finally {
            lock.unlock();
        }
    }

    public Participante buscar(Pescador perfil) {
        lock.lock();
        try {
            return buscarInterno(perfil);
        } finally {
            lock.unlock();
        }
    }

    public boolean temVaga() {
        lock.lock();
        try {
            return estado == Estado.ESPERANDO && participantes.size() < 2;
        } finally {
            lock.unlock();
        }
    }

    public boolean isTerminada() {
        lock.lock();
        try {
            return estado == Estado.TERMINADA;
        } finally {
            lock.unlock();
        }
    }

    /** Partida abandonada que pode ser descartada. */
    public boolean obsoleta() {
        lock.lock();
        try {
            long agora = agora();
            if (estado == Estado.TERMINADA) return agora - terminouEm > 300_000;
            if (participantes.isEmpty()) return true;
            if (estado == Estado.ESPERANDO) {
                return agora - participantes.get(0).ultimoContato > 30_000;
            }
            for (Participante p : participantes) {
                if (agora - p.ultimoContato < 60_000) return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** true uma vez quando há perfis alterados que precisam ir para o arquivo. */
    public boolean pegarSalvarPendente() {
        lock.lock();
        try {
            boolean v = salvarPendente;
            salvarPendente = false;
            return v;
        } finally {
            lock.unlock();
        }
    }

    // ---------- Jogadas ----------

    public Jogada pescar(Participante p, int forcaPedida) {
        lock.lock();
        try {
            tocar(p);
            verificar();
            Jogada j = new Jogada();
            long agora = agora();

            if (estado == Estado.ESPERANDO) return recusar(j, p, 409, "Esperando o outro jogador entrar...");
            if (estado == Estado.TERMINADA) return recusar(j, p, 409, "A partida já terminou.");
            if ("descansando".equals(p.acao) && agora < p.ocupadoAte) {
                return recusar(j, p, 409, "Você está descansando...");
            }
            if (agora - p.ultimaPesca < INTERVALO_PESCA_MS) {
                return recusar(j, p, 429, "Calma! Espere um instante para pescar de novo.");
            }
            if (p.energia <= 0) return recusar(j, p, 409, "Sem energia! Descanse um pouco.");

            int forca = p.limitarForca(forcaPedida);
            int idx = sortear(forca);
            if (idx < 0) {
                finalizar("O lago ficou vazio!", null);
                return recusar(j, p, 409, "O lago ficou vazio!");
            }

            // --- região crítica: tira o peixe do lago compartilhado ---
            p.ultimaPesca = agora;
            p.energia = Math.max(0, p.energia - Pescador.custo(forca));
            estoque[idx]--;
            Peixe peixe = CATALOGO[idx];
            p.peixes[idx]++;
            p.moedas += peixe.getValor();
            p.acao = "pescando";
            p.ocupadoAte = agora + PESCA_MS;

            synchronized (p.perfil) {
                p.perfil.adicionarMoedas(peixe.getValor());
                p.perfil.subirNivel();
            }
            salvarPendente = true;

            // --- mensagens para as duas caixas ---
            boolean raro = peixe.getRaridade() != Raridade.COMUM && peixe.getRaridade() != Raridade.INCOMUM;
            difundir(raro ? "raro" : "evento", p.nome,
                    "pescou " + peixe.getNome() + " (+" + peixe.getValor() + " 🪙)");
            if (estoque[idx] == 0) {
                difundir("sistema", "", "O lago ficou sem " + peixe.getNome() + "!");
            }
            if (totalEstoque() == 0) {
                finalizar("O lago ficou vazio!", null);
            }

            j.peixe = peixe;
            j.forca = forca;
            j.energia = p.energia;
            j.cansado = p.cansado();
            j.moedas = p.moedas;
            return j;
        } finally {
            lock.unlock();
        }
    }

    public Jogada descansar(Participante p) {
        lock.lock();
        try {
            tocar(p);
            verificar();
            Jogada j = new Jogada();
            long agora = agora();

            if (estado != Estado.JOGANDO) return recusar(j, p, 409, "A partida não está em andamento.");
            if ("descansando".equals(p.acao) && agora < p.ocupadoAte) {
                return recusar(j, p, 409, "Calma! Você já está descansando.");
            }

            p.energia = Math.min(Pescador.ENERGIA_MAX, p.energia + Pescador.RECUPERA_DESCANSO);
            p.acao = "descansando";
            p.ocupadoAte = agora + DESCANSO_MS;
            difundir("evento", p.nome, "está descansando 😴");

            j.energia = p.energia;
            j.cansado = p.cansado();
            j.moedas = p.moedas;
            return j;
        } finally {
            lock.unlock();
        }
    }

    // ---------- Mensagens ----------

    public void enviarChat(Participante de, String texto) {
        lock.lock();
        try {
            tocar(de);
            long agora = agora();
            if (agora - de.ultimaMsg < 400) return; // anti-spam
            String t = limparTexto(texto);
            if (t.isEmpty()) return;
            de.ultimaMsg = agora;
            difundir("chat", de.nome, t);
        } finally {
            lock.unlock();
        }
    }

    /** Estado completo da partida (JSON) + mensagens novas da caixa do jogador. */
    public String estadoJson(Participante eu, long desde) {
        lock.lock();
        try {
            tocar(eu);
            verificar();
            long agora = agora();
            Participante rival = outro(eu);

            long restante = estado == Estado.JOGANDO ? Math.max(0, fimPrevisto - agora)
                    : (estado == Estado.ESPERANDO ? DURACAO_MS : 0);

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"partida\":{")
              .append("\"id\":").append(id).append(",")
              .append("\"estado\":\"").append(estado).append("\",")
              .append("\"restante\":").append(restante).append(",")
              .append("\"vencedor\":").append(vencedor == null ? "null" : "\"" + esc(vencedor.nome) + "\"").append(",")
              .append("\"motivo\":\"").append(esc(motivo)).append("\"},");

            sb.append("\"eu\":").append(jogadorJson(eu, agora, false)).append(",");
            sb.append("\"adversario\":").append(rival == null ? "null" : jogadorJson(rival, agora, true)).append(",");

            sb.append("\"lago\":[");
            for (int i = 0; i < CATALOGO.length; i++) {
                Peixe f = CATALOGO[i];
                if (i > 0) sb.append(",");
                sb.append("{\"raridade\":\"").append(f.getRaridade()).append("\",")
                  .append("\"nome\":\"").append(esc(f.getNome())).append("\",")
                  .append("\"valor\":").append(f.getValor()).append(",")
                  .append("\"restante\":").append(estoque[i]).append(",")
                  .append("\"voce\":").append(eu.peixes[i]).append(",")
                  .append("\"rival\":").append(rival == null ? 0 : rival.peixes[i]).append("}");
            }
            sb.append("],");

            sb.append("\"mensagens\":[");
            boolean primeira = true;
            for (Mensagem m : eu.caixa) {
                if (m.id <= desde) continue;
                if (!primeira) sb.append(",");
                primeira = false;
                sb.append("{\"id\":").append(m.id)
                  .append(",\"tipo\":\"").append(m.tipo)
                  .append("\",\"autor\":\"").append(esc(m.autor))
                  .append("\",\"texto\":\"").append(esc(m.texto)).append("\"}");
            }
            sb.append("]}");
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    // ---------- Internos (sempre chamados com o lock já pego) ----------

    private Participante buscarInterno(Pescador perfil) {
        for (Participante p : participantes) {
            if (p.perfil == perfil) return p;
        }
        return null;
    }

    private Participante outro(Participante p) {
        for (Participante x : participantes) {
            if (x != p) return x;
        }
        return null;
    }

    private void tocar(Participante p) {
        p.ultimoContato = agora();
    }

    private Jogada recusar(Jogada j, Participante p, int status, String erro) {
        j.erro = erro;
        j.status = status;
        j.energia = p.energia;
        j.cansado = p.cansado();
        j.moedas = p.moedas;
        return j;
    }

    private int totalEstoque() {
        int t = 0;
        for (int e : estoque) t += e;
        return t;
    }

    /** Sorteia um peixe que ainda existe no lago. -1 se o lago está vazio. */
    private int sortear(int forca) {
        int[] pesos = PESOS[forca < 35 ? 0 : (forca < 70 ? 1 : 2)];
        int total = 0;
        for (int i = 0; i < CATALOGO.length; i++) {
            if (estoque[i] > 0) total += pesos[i];
        }
        if (total == 0) return -1;

        int sorteio = random.nextInt(total);
        for (int i = 0; i < CATALOGO.length; i++) {
            if (estoque[i] <= 0) continue;
            sorteio -= pesos[i];
            if (sorteio < 0) return i;
        }
        return -1;
    }

    /** Confere tempo esgotado e jogador ausente. */
    private void verificar() {
        if (estado != Estado.JOGANDO) return;
        long agora = agora();
        if (agora >= fimPrevisto) {
            finalizar("Tempo esgotado!", null);
            return;
        }
        for (Participante p : participantes) {
            if (agora - p.ultimoContato > AUSENTE_MS) {
                finalizar(p.nome + " saiu da partida.", outro(p));
                return;
            }
        }
    }

    private void finalizar(String motivoFim, Participante vencedorForcado) {
        if (estado == Estado.TERMINADA) return;
        estado = Estado.TERMINADA;
        terminouEm = agora();
        motivo = motivoFim;

        if (vencedorForcado != null) {
            vencedor = vencedorForcado;
        } else if (participantes.size() == 2) {
            Participante a = participantes.get(0);
            Participante b = participantes.get(1);
            vencedor = a.moedas > b.moedas ? a : (b.moedas > a.moedas ? b : null);
        }

        if (vencedor != null) {
            synchronized (vencedor.perfil) {
                vencedor.perfil.adicionarMoedas(BONUS_VITORIA);
            }
            salvarPendente = true;
            difundir("sistema", "", "Fim de jogo! " + motivoFim + " Vencedor: " + vencedor.nome
                    + " (+" + BONUS_VITORIA + " 🪙 de bônus)");
        } else {
            difundir("sistema", "", "Fim de jogo! " + motivoFim + " Empate!");
        }
    }

    /** Entrega a mensagem na caixa de cada jogador. */
    private void difundir(String tipo, String autor, String texto) {
        Mensagem m = new Mensagem(++seqMensagem, tipo, autor, texto);
        for (Participante p : participantes) {
            p.caixa.add(m);
            if (p.caixa.size() > MAX_CAIXA) p.caixa.remove(0);
        }
    }

    private String jogadorJson(Participante p, long agora, boolean rival) {
        String acao = agora < p.ocupadoAte ? p.acao : "livre";
        boolean ausente = rival && estado == Estado.JOGANDO && agora - p.ultimoContato > 6_000;
        return "{\"nome\":\"" + esc(p.nome) + "\","
             + "\"moedas\":" + p.moedas + ","
             + "\"energia\":" + p.energia + ","
             + "\"cansado\":" + p.cansado() + ","
             + "\"acao\":\"" + acao + "\","
             + "\"ausente\":" + ausente + "}";
    }

    private static String limparTexto(String bruto) {
        if (bruto == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : bruto.toCharArray()) {
            if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        String s = sb.toString().trim();
        if (s.codePointCount(0, s.length()) > 140) {
            s = s.substring(0, s.offsetByCodePoints(0, 140)).trim();
        }
        return s;
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
}