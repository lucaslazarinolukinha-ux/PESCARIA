package br.com.pesca.main;

import br.com.pesca.model.Pescador;
import br.com.pesca.model.Peixe;
import br.com.pesca.model.Raridade;

import java.util.Random;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);
        Random random = new Random();

        System.out.print("Digite o nome do usuario: ");
        String nome = scanner.nextLine();

        Pescador jogador = new Pescador(nome);

        Peixe[] peixes = {
            new Peixe("Tilápia", Raridade.COMUM, 20),
            new Peixe("Carpa", Raridade.INCOMUM, 40),
            new Peixe("Salmão", Raridade.RARO, 80),
            new Peixe("Tubarão", Raridade.EPICO, 150),
            new Peixe("Megalodon", Raridade.LENDARIO, 400)
        };

        int opcao;

        do {
            System.out.println("\n=== JOGO DE PESCA ===");
            System.out.println("Pescador: " + jogador.getNome());
            System.out.println("Nível: " + jogador.getNivel());
            System.out.println("Moedas: " + jogador.getMoedas());

            System.out.println("\n1 - Pescar");
            System.out.println("2 - Descansar");
            System.out.println("0 - Sair");
            System.out.print("Escolha: ");

            opcao = scanner.nextInt();

            switch (opcao) {

                case 1:
                    Peixe pescado = peixes[random.nextInt(peixes.length)];

                    System.out.println("\nVocê pescou: " + pescado.getNome());
                    System.out.println("Raridade: " + pescado.getRaridade());
                    System.out.println("Valor: " + pescado.getValor() + " moedas");

                    jogador.adicionarMoedas(pescado.getValor());
                    jogador.subirNivel();

                    System.out.println("Você ganhou " + pescado.getValor() + " moedas!");
                    break;

                case 2:
                    System.out.println("Você descansou.");
                    break;

                case 0:
                    System.out.println("Obrigado por jogar!");
                    break;

                default:
                    System.out.println("Opção inválida!");
            }

        } while (opcao != 0);

        scanner.close();
    }
}