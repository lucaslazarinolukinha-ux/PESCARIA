package br.com.pesca.model;

public class Peixe {
    private String nome;
    private Raridade raridade;
    private int valor;

    public Peixe(String nome, Raridade raridade, int valor) {
        this.nome = nome;
        this.raridade = raridade;
        this.valor = valor;
    }

    public String getNome() {
        return nome;
    }

    public Raridade getRaridade() {
        return raridade;
    }

    public int getValor() {
        return valor;
    }
}