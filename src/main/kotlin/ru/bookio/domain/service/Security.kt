package ru.bookio.domain.service

interface PasswordHasher { fun hash(password: String): String; fun verify(password: String, hash: String): Boolean }
interface Tokens { fun generate(): String; fun hash(token: String): String }
