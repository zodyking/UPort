package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkParseTest {
    @Test
    fun controllerListsAccessPointsAndWifiSpeeds() {
        val devices = parseControllerDevices(
            """
            {"data":[
              {"mac":"aa:bb","name":"Hall AP","model":"U7-Pro","type":"uap","state":1,"ip":"10.0.0.2","num_sta":4,"adopted":true},
              {"mac":"cc:dd","name":"Rack","model":"USW-24","type":"usw","state":1,"ip":"10.0.0.3","adopted":true}
            ]}
            """.trimIndent(),
        )
        assertEquals("Access point", devices[0].kind)
        assertEquals(4, devices[0].clientCount)
        assertEquals("Switch", devices[1].kind)

        val clients = parseControllerClients(
            """
            {"data":[
              {"mac":"11:22","name":"Phone","ip":"10.0.0.20","is_wired":false,"essid":"APT","ap_mac":"aa:bb","signal":-54,"rx_rate":240000,"tx_rate":54000}
            ]}
            """.trimIndent(),
        )
        val phone = clients.single()
        assertEquals("Wi-Fi", phone.connection)
        assertEquals("APT", phone.ssid)
        assertEquals(-54, phone.signalDbm)
        assertEquals(240.0, phone.downMbps!!, 0.1)
        assertEquals(54.0, phone.upMbps!!, 0.1)
        val named = nameAccessPoints(clients, devices)
        assertEquals("Hall AP", named.single().accessPoint)
        assertTrue(named.single().online)
    }
}
