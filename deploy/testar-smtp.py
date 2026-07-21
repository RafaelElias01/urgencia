#!/usr/bin/env python3
"""Teste isolado de autenticacao SMTP do Gmail, sem passar pelo app Java.
Rode direto no terminal integrado do Codespace (nao peça pro Claude rodar):

    python3 deploy/testar-smtp.py

A senha e digitada de forma oculta (getpass) e nunca aparece na tela nem fica
salva em lugar nenhum.
"""
import smtplib
import getpass

email = input("E-mail (ex.: centraldetransplante@gmail.com): ").strip()
senha = getpass.getpass("Senha de app (16 caracteres, sem espaco): ").strip()

try:
    s = smtplib.SMTP("smtp.gmail.com", 587, timeout=15)
    s.set_debuglevel(1)
    s.starttls()
    s.login(email, senha)
    print("\nOK - autenticacao funcionou")
    s.quit()
except Exception as e:
    print("\nFALHOU:", repr(e))
