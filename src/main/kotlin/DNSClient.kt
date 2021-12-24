import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.IllegalStateException
import kotlin.system.exitProcess

class DNSClient(
    private val inetAddress: InetAddress = InetAddress.getByName("10.0.0.1"), //InetAddress.getLocalHost(),
    private val port: Int = 53
) {
    private val socket = DatagramSocket()
    private var question = byteArrayOf()

    init {
        println("Клиент запущен")
        readConsole()
    }

    private fun readConsole() {
        while (true) {
            println("\nВыберите команду:")
            println("1 - A\n15 - MX\n16 - TXT\n28 - AAAA")

            val command = readLine()
            if (command.isNullOrEmpty()) {
                println("Введена некорректная команда")
                continue
            }

            val commandI: Int
            try {
                commandI = command.toInt()
            } catch (e: NumberFormatException) {
                println("Введена некорректная команда")
                continue
            }

            val qType = when (commandI) {  //проверять на int
                1 -> QType.A
                15 -> QType.MX
                16 -> QType.TXT
                28 -> QType.AAAA
                else -> {
                    println("Введена некорректная команда")
                    continue
                }
            }

            var domain: String? = "google.com"

            var repeat = true
            while (repeat) {
                println("Введите доменное имя:")
                domain = readLine()
                if (domain == null || domain.startsWith("--stop"))
                    exitProcess(0)
                if (domain.isEmpty()) {
                    println("Введено некорректное доменное имя")
                    continue
                }
                repeat = false
            }

            request(domain!!, qType)
        }
    }

    private fun request(domainName: String, qType: QType) {
        // Формируем пакет и отправляем его
        question = question(domainName, qType)
        val sendBuff = header() + question
        val outputPacket = DatagramPacket(sendBuff, sendBuff.size, inetAddress, port)
        socket.send(outputPacket)

        waitAnswer(sendBuff.size - 1)    // Ждём ответ и обрабатываем его
    }

    fun header(): ByteArray {
        val requestNum = byteArrayOf(0.toByte(), 1.toByte())
        // формирование 2 "строки" заголовка
        val qr = QR.QUERY.code              // запрос
        val opcode = Opcode.QUERY.code      // тип запроса - query, 4 бита
        val aaTcRdRa = 0
        val zRCode = 0
        val flags1 = (qr shl 7) or (opcode shl 3) or (aaTcRdRa shr 1)
        val flags2 = (aaTcRdRa shl 7) or zRCode
        val flags = byteArrayOf(flags1.toByte(), flags2.toByte())
        val qdCount = 1 // отправляем по одному запросу
        val zero2byte = byteArrayOf(0.toByte(), 0.toByte())

        return requestNum +
                flags + 0.toByte() +
                qdCount.toByte() +
                zero2byte + zero2byte + zero2byte
    }

    private fun question(domainName: String = "google.com", qType: QType = QType.A): ByteArray {
        // Формируем QNAME
        val splitDomain = domainName.split(".")
        val arraysDomain = mutableListOf<ByteArray>()

        for (domain in splitDomain)
            arraysDomain.add(domain.toByteArray(Charsets.US_ASCII))

        val qName = mutableListOf<Byte>()

        for (domainB in arraysDomain) {
            qName.add(domainB.size.toByte())
            qName.addAll(domainB.toList())
        }

        qName.add(0.toByte())

        // Формируем QTYPE
        val qT = byteArrayOf((if (qType.code < 255) 0 else (qType.code shr 8)).toByte(), qType.code.toByte())

        // Формируем QCLASS
        val qClass = byteArrayOf(0.toByte(), 1.toByte())  // или 255 в конце = ALL

        return qName.toByteArray() + qT + qClass
    }


    private fun waitAnswer(questionLastInd: Int) {
        // Ожидаем ответ
        while (true) {
            val getBuff = ByteArray(1024)
            val inputPacket = DatagramPacket(getBuff, getBuff.size)
            socket.receive(inputPacket)

            val data = inputPacket.data

            // Парсим Header
            val headerB = data.slice(0 until 12)     // 12 байт - заголовок
            var header: Header
            try {
                header = parseHeader(headerB)
            } catch (e: IllegalStateException) {
                println(e.message)
                println("Ждём следующий ответ.")
                continue
            }

            if (header.isEmpty())   // значит, сервер прислал ошибку, которые мы уже вывели на экран
                break

            // Проверяем QUESTION на соответствие с тем, которое отправляли
            val questionInp = data.slice(12..questionLastInd)
            if (question.toList() != questionInp) {
                println("Был получен пакет с неподходящим QUESTION.")
                break
            }

            // Получаем количество ответов
            val answersNum = header.ANCOUNT
            val answers = parseAnswer(data.toList(), questionLastInd, answersNum)

            for (ans in answers)
                println("${ans.NAME} - ${ans.RDATA}")     // Выводим информацию на печать

            break
        }
    }

    private fun parseHeader(header: List<Byte>): Header {
        if (header.size != 12)
            throw IllegalArgumentException("Неверный размер header. Должен быть 12, а получили ${header.size}")

        val id = header.slice(0..1).toInt()

        if (id != 1)
            throw IllegalStateException("Пришёл ответ не на тот запрос (id != 1).")

        val qrOpcodeAaTcRd = header[2].toUByte().toInt()
        val qr = when (val qrI = qrOpcodeAaTcRd shr 7) {
            0 -> QR.QUERY
            1 -> QR.RESPONSE
            else -> throw IllegalArgumentException("Был получен пакет с неизвестным qr = $qrI.")
        }

        if (qr == QR.QUERY)
            throw IllegalArgumentException("Был получен пакет с qr = QUERY вместо ANSWER")

        val opcodeI =
            (qrOpcodeAaTcRd and 120) shr 3    // накладываем маску 01111000, чтобы получить только opcode и двигаем
        val opcode = when (opcodeI) {
            0 -> Opcode.QUERY
            1 -> Opcode.IQUERY
            2 -> Opcode.STATUS
            else -> throw IllegalArgumentException("Был получен пакет с неизвестным opcode = $opcodeI")
        }

        val aa = (qrOpcodeAaTcRd and 4) shr 2   // накладываем маску 0000'0100 и двигаем
        val tc = (qrOpcodeAaTcRd and 2) shr 1   // накладываем маску 0000'0010 и двигаем
        val rd = qrOpcodeAaTcRd and 1           // накладываем маску 0000'0001

        val raZRcode = header[3].toUByte().toInt()
        val ra = (raZRcode and 128) shr 7       // накладываем маску 1000'0000 и двигаем
        val rcode = when (val rcodeI = raZRcode and 15) {            // накладываем маску 0000'1111
            0 -> Rcode.OK
            1 -> Rcode.FORMAT_ERROR
            2 -> Rcode.SERVER_FAILURE
            3 -> Rcode.NAME_ERROR
            4 -> Rcode.NOT_IMPLEMENTED
            5 -> Rcode.REFUSED
            else -> throw IllegalArgumentException("Был получен пакет с неизвестным RCODE = $rcodeI")
        }

        if (rcode != Rcode.OK) {
            printError(rcode)
            return emptyHeader()
        }

        val qdCount = header.slice(4..5).toInt()
        if (qdCount != 1)
            throw IllegalArgumentException("Был получен пакет с QDCOUNT = $qdCount, хотя отправлялся один запрос.")

        val anCount = header.slice(6..7).toInt()
        if (anCount < qdCount)
            throw IllegalArgumentException("Был получен пакет с ANCOUNT = $anCount, хотя был 1 запрос.")

        val nsCount = header.slice(8..9).toInt()
        val arCount = header.slice(10..11).toInt()

        return Header(id, qr, opcode, aa, tc, rd, ra, rcode, qdCount, anCount, nsCount, arCount)
    }

    // Возвращает пару (String, Int), где Int - это index последнего байта доменного имени
    private fun parseDomainName(data: List<Byte>, startInd: Int): Pair<String, Int> {
        val domain = StringBuilder()
        var i = startInd

        while (true) {
            val byte = data[i]
            val count = byte.toUByte().toInt()
            i++

            if ((count and 192) shr 6 == 3) {   // применяем маску 1100'0000, чтобы узнать, нет ли ссылки
                // применяем маску 0011'1111, чтобы узнать первую часть ссылки, и добавляем вторую часть ссылки
                val link = ((count and 63) shl 8) or data[i].toUByte().toInt()  // получаем ссылку
                val linkDomain = parseDomainName(data, link)
                domain.append(linkDomain.first)     // и вызываем повторно функцию
                break
            }

            if (count != 0) {
                if (i != startInd + 1)
                    domain.append(".")

                val str = data.slice(i until i + count).toByteArray().toString(Charsets.US_ASCII)
                i += count
                domain.append(str)
            } else {
                i--
                break
            }
        }
        return domain.toString() to i
    }

    private fun parseAnswer(data: List<Byte>, questionLastInd: Int, ansNum: Int): List<Answer> {
        val answers = mutableListOf<Answer>()
        var i = questionLastInd

        for (count in 1..ansNum) {
            val domainToInd = parseDomainName(data, i + 1)
            i = domainToInd.second + 1

            val type = when (val typeI = data.slice(i..i + 1).toInt()) {
                1 -> QType.A
                15 -> QType.MX
                16 -> QType.TXT
                28 -> QType.AAAA
                else -> throw IllegalArgumentException("Был получен пакет с неизвестным TYPE = $typeI")
            }
            i += 2

            val classI = data.slice(i..i + 1).toInt()     // 2 байта
            i += 2
            val ttl = data.slice(i..i + 3).toInt()        // 4 байта
            i += 4
            val dataLength = data.slice(i..i + 1).toInt() // 2 байта
            i += 2
            val dataB = data.slice(i until i + dataLength)

            val dataAns = when (type) {
                QType.A, QType.AAAA -> {
                    var ind = 0
                    val str = StringBuilder(dataB[ind].toUByte().toInt().toString())
                    while (ind != dataB.size - 1) {
                        ind++
                        str.append(".")
                        str.append(dataB[ind].toUByte().toInt().toString())
                    }
                    str.toString()
                }
                QType.MX -> {
                    val preference = dataB.slice(0..1).toInt()
                    val exchanger = parseDomainName(data, i + 2).first
                    "\tMX preference = $preference, exchanger = $exchanger"
                }

                QType.TXT -> {
                    val str = StringBuilder()
                    var ind = 0
                    while (true) {
                        val b = dataB[ind]
                        ind++
                        val strLen = b.toUByte().toInt()
                        str.append(dataB.slice(ind until ind + strLen).toByteArray().toString(Charsets.US_ASCII))
                        ind += strLen - 1

                        if (ind == dataB.size - 1)
                            break
                        else
                            str.append("\n")
                    }
                    str.toString()
                }
            }

            i += dataLength - 1
            answers.add(Answer(domainToInd.first, type, classI, ttl, dataLength, dataAns))
        }
        return answers
    }

    private fun emptyHeader(): Header = Header(0, QR.QUERY, Opcode.QUERY, 0, 0, 0, 0, Rcode.OK, 0, 0, 0, 0)

    private fun printError(rcode: Rcode) {
        val msg = when (rcode) {
            Rcode.OK -> ""
            Rcode.FORMAT_ERROR -> "RCODE = ${rcode.code}: Сервер отвечает, что не смог понять запрос."
            Rcode.SERVER_FAILURE -> "RCODE = ${rcode.code}: Сервер отвечает, что сейчас у него проблемы."
            Rcode.NAME_ERROR -> "RCODE = ${rcode.code}: Сервер отвечает, что доменное имя, указанное в запросе, не существует."
            Rcode.NOT_IMPLEMENTED -> "RCODE = ${rcode.code}: Сервер отвечает, что не поддерживает запрошенный тип запроса."
            Rcode.REFUSED -> "RCODE = ${rcode.code}: Сервер отвечает, что отказывается выполнять указанную операцию по соображениям политики."
        }
        println(msg)
    }
}

data class Header(
    val ID: Int,
    val qr: QR,
    val opcode: Opcode,
    val AA: Int,
    val TC: Int,
    val RD: Int,
    val RA: Int,
    val RCODE: Rcode,
    val QDCOUNT: Int,
    val ANCOUNT: Int,
    val NSCOUNT: Int,
    val ARCOUNT: Int
) {
    fun isEmpty() = ID == 0 && qr.code == 0 && opcode.code == 0 && AA == 0 && TC == 0 && RD == 0 && RA == 0 &&
            RCODE.code == 0 && QDCOUNT == 0 && ANCOUNT == 0 && NSCOUNT == 0 && ARCOUNT == 0
}

data class Answer(
    val NAME: String,   // имя домена
    val TYPE: QType,
    val CLASS: Int,
    val TTL: Int,
    val RDLENGTH: Int,
    val RDATA: String
)

enum class QR(val code: Int) {
    QUERY(0),
    RESPONSE(1)
}

enum class Opcode(val code: Int) {
    QUERY(0),   // стандартный запрос
    IQUERY(1),  // обратный (инверсированный) запрос
    STATUS(2)   // запрос состояния сервера
}

enum class Rcode(val code: Int) {
    OK(0),  // всё ок, нет ошибок
    FORMAT_ERROR(1),    // ошибка у меня, не смог понять запрос
    SERVER_FAILURE(2),  // ошибка сервера, проблема у него
    NAME_ERROR(3),      // доменное имя, указанное в запросе, не существует
    NOT_IMPLEMENTED(4), // сервер не поддерживает запрошенный тип запроса
    REFUSED(5)          // сервер отказал в обработке запроса (не должно быть)
}


enum class QType(val code: Int) {
    A(1),       // адрес узла
    MX(15),     // почтовый обмен
    TXT(16),    // текстовые строки
    AAAA(28)
}

fun List<Byte>.toInt(): Int {
    var result = 0
    var shift = 0
    this.reversed().forEach {
        result += it.toUByte().toInt() shl shift
        shift += 8
    }
    return result
}