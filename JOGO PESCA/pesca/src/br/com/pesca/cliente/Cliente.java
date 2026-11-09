package br.com.pesca.cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

/**
 * Cliente do jogo de pesca.
 * Coloque em OUTRO projeto (outro VS Code), no pacote br.com.pesca.cliente.
 * Ele não tem lógica de jogo: só mostra o que o servidor manda
 * e envia o que o usuário digita.
 */
public class Cliente {

    private static final String HOST = "localhost"; // troque pelo IP do servidor se for outro PC
    private static final int PORTA = 5000;
    private static final String ENTRADA = "@@ENTRADA@@";
    private static final String FIM = "@@FIM@@";

    public static void main(String[] args) {
        try (
            Socket socket = new Socket(HOST, PORTA);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Scanner scanner = new Scanner(System.in)
        ) {
            String linha;

            while ((linha = in.readLine()) != null) {
                if (linha.equals(FIM)) {
                    break;
                } else if (linha.equals(ENTRADA)) {
                    out.println(scanner.nextLine());
                } else if (linha.endsWith(": ")) {
                    // prompts ("Escolha: ") ficam na mesma linha do cursor
                    System.out.print(linha);
                } else {
                    System.out.println(linha);
                }
            }

            System.out.println("\nConexão encerrada.");

        } catch (IOException e) {
            System.out.println("Não foi possível conectar ao servidor: " + e.getMessage());
            System.out.println("Verifique se o Servidor está rodando e se host/porta estão corretos.");
        }
    }
}