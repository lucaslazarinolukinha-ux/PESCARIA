package br.com.pesca.model;

public class Pescador {
    public static final int ENERGIA_MAX = 100;
    public static final int LIMITE_CANSACO = 30;     // abaixo disso, está cansado
    public static final int FORCA_MAX_CANSADO = 40;  // força máxima quando cansado
    public static final int RECUPERA_DESCANSO = 40;

    private String nome;
    private int nivel = 1;
    private int moedas = 0;
    private int energia = ENERGIA_MAX;

    public Pescador(String nome) {
        this.nome = nome;
    }

    public String getNome() { return nome; }
    public int getNivel() { return nivel; }
    public int getMoedas() { return moedas; }
    public int getEnergia() { return energia; }

    public void adicionarMoedas(int quantidade) { moedas += quantidade; }
    public void subirNivel() { nivel++; }

    /** Energia gasta por lançamento: de 5 (força 10) até 45 (força 100). */
    public static int custo(int forca) {
        return 5 + forca * 40 / 100;
    }

    public boolean isCansado() {
        return energia < LIMITE_CANSACO;
    }

    /** Garante força entre 10 e o máximo permitido (menor se estiver cansado). */
    public int limitarForca(int forca) {
        int max = isCansado() ? FORCA_MAX_CANSADO : 100;
        return Math.max(10, Math.min(forca, max));
    }

    public void gastarEnergia(int quantidade) {
        energia = Math.max(0, energia - quantidade);
    }

    public void descansar() {
        energia = Math.min(ENERGIA_MAX, energia + RECUPERA_DESCANSO);
    }
}