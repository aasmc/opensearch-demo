package ru.demo.logsender.generator;

/**
 * Вид пачки логов. Монитор алертинга срабатывает только на {@link #IMPORTANT_WITH_PROBLEM}: в пачке есть важная
 * фраза и хотя бы одно сообщение уровня WARN или ERROR.
 */
public enum BatchType {

    /** Без важной фразы, уровни сообщений случайные, включая WARN и ERROR. */
    REGULAR,

    /** Одно сообщение на INFO содержит важную фразу, остальные — только TRACE, DEBUG или INFO. */
    IMPORTANT,

    /** Как {@link #IMPORTANT}, но ещё одно сообщение (не с важной фразой) имеет уровень WARN или ERROR. */
    IMPORTANT_WITH_PROBLEM
}
