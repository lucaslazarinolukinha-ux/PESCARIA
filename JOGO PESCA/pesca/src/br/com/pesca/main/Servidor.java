package br.com.pesca.main;

import br.com.pesca.model.Pescador;
import br.com.pesca.model.Peixe;
import br.com.pesca.model.Raridade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;


public class Servidor {

    private static final int PORTA = 5000;
    private static final String ENTRADA = "@@ENTRADA@@";
    private static final String FIM = "@@FIM@@";

    public static void main(String[] args) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("Servidor de pesca rodando na porta " + PORTA + "...");

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Cliente conectado: " + socket.getInetAddress());
                new Thread(() -> atender(socket)).start();
            }
        }
    }

    private static void atender(Socket socket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            Random random = new Random();

            out.println("Digite o nome do usuario: ");
            out.println(ENTRADA);
            String nome = in.readLine();
            if (nome == null) return;

            Pescador jogador = new Pescador(nome);

            Peixe[] peixes = {
                new Peixe("Tilápia", Raridade.COMUM, 20),
                new Peixe("Carpa", Raridade.INCOMUM, 40),
                new Peixe("Salmão", Raridade.RARO, 80),
                new Peixe("Tubarão", Raridade.EPICO, 150),
                new Peixe("Megalodon", Raridade.LENDARIO, 400)
            };

            int opcao = -1;

            do {
                out.println("\n=== JOGO DE PESCA ===");
                out.println("Pescador: " + jogador.getNome());
                out.println("Nível: " + jogador.getNivel());
                out.println("Moedas: " + jogador.getMoedas());
                out.println("\n1 - Pescar");
                out.println("2 - Descansar");
                out.println("0 - Sair");
                out.println("Escolha: ");
                out.println(ENTRADA);

                String linha = in.readLine();
                if (linha == null) break; // cliente caiu

                try {
                    opcao = Integer.parseInt(linha.trim());
                } catch (NumberFormatException e) {
                    out.println("Digite apenas números!");
                    opcao = -1;
                    continue;
                }

                switch (opcao) {
                    case 1:
                        Peixe pescado = peixes[random.nextInt(peixes.length)];

                        out.println("\nVocê pescou: " + pescado.getNome());
                        out.println("Raridade: " + pescado.getRaridade());
                        out.println("Valor: " + pescado.getValor() + " moedas");

                        jogador.adicionarMoedas(pescado.getValor());
                        jogador.subirNivel();

                        out.println("Você ganhou " + pescado.getValor() + " moedas!");
                        break;

                    case 2:
                        out.println("Você descansou.");
                        break;

                    case 0:
                        out.println("Obrigado por jogar!");
                        break;

                    default:
                        out.println("Opção inválida!");
                }

            } while (opcao != 0);

            out.println(FIM);

        } catch (IOException e) {
            System.out.println("Conexão encerrada: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}