function changeView(radio)
{
    if (radio.value == "console")
    {
        document.getElementById('console').style.display = 'block';
    }
}

function capitalize(s)
{
    if (typeof s !== 'string')
        return '';
    if (s.length == 0)
        return '';
    return s.charAt(0).toUpperCase() + s.slice(1);
}

function logIt(message)
{
    var console = document.getElementById('console');
    var d = new Date();
    var dString = d.toLocaleTimeString();
    var msgSplit = message.split(/\r?\n/);
    for (var i = 0; i < msgSplit.length; i++)
    {
        console.innerHTML += "&lt;" + dString + "&gt; " + msgSplit[i] + "<br />";
    }
    window.scrollTo(0,document.body.scrollHeight);
}

function blobToHTML(fileName, blob)
{
    var type = blob.type;
    if (type.startsWith("image/"))
    {
        var newImage = document.createElement('img');
        newImage.src = URL.createObjectURL(blob);
        return newImage.outerHTML;
    } else if (type.startsWith("audio/") && !type.startsWith("audio/mid")) {
        return "<audio controls src=\"" + URL.createObjectURL(blob) + "\" />";
    } else if (type.startsWith("video/")) {
        return  "<video controls><source type=\"" + type + "\" src=\"" + URL.createObjectURL(blob) + "\"></video>";
    } else {
        return "<a download=\"" + fileName + "\" href=\"" + URL.createObjectURL(blob) + "\"><b>" + fileName + "</b></a>";    
    }
}

var routeput = new RouteputConnection("routeputDebug", sessionStorage.getItem("routeputAdminPassword") || undefined);
//routeput.debug = true;

function showAdminAuthModal(message)
{
    var modal = document.getElementById('adminAuthModal');
    if (!modal) return;
    var wasVisible = modal.style.display === 'flex';
    modal.style.display = 'flex';
    var err = document.getElementById('adminAuthError');
    if (message)
    {
        err.textContent = message;
        err.style.display = 'block';
    } else {
        err.style.display = 'none';
    }
    // Only reset the input the first time the modal opens; otherwise we'd wipe
    // what the user is currently typing on every retry.
    if (!wasVisible)
    {
        var input = document.getElementById('adminAuthInput');
        input.value = '';
        input.focus();
    }
}

function hideAdminAuthModal()
{
    var modal = document.getElementById('adminAuthModal');
    if (modal) modal.style.display = 'none';
}

routeput.onauthrequired = function(channel, text) {
    sessionStorage.removeItem("routeputAdminPassword");
    showAdminAuthModal(text || "Admin password required.");
};

routeput.onblob = function(name, blob) {
    logIt(blobToHTML(name, blob));
}

routeput.onmessage = function (member, messageType, jsonObject) {
    var routePutMeta = jsonObject.__routeput;
    if (messageType == "info")
    {
        logIt(jsonObject.text);
    } else if (messageType == "warning") {
        logIt("<b style=\"color: #AAAA00;\">" + jsonObject.text + "</b>");
    } else if (messageType == "error") {
        logIt("<b style=\"color: red;\">" + jsonObject.text + "</b>");
    } else if (jsonObject.hasOwnProperty('channelStats')) {
        var channelStats = jsonObject.channelStats;
        var channelStatsTable = document.getElementById('channelStatsTable');
        var i;
        for (i = 0; i < channelStatsTable.children.length; i++)
        {
            var child = channelStatsTable.children[i];
            var cName = child.id.slice(0, -2);
            if (!channelStats.hasOwnProperty(cName) && child.id.endsWith("TR"))
            {
                console.log("Removing missing channel: " + cName);
                channelStatsTable.removeChild(child);
            }
        }

        for (var key in channelStats)
        {
            //console.log(key);
            var value = channelStats[key];
            var icons = "";
            //console.log(value)
            var channelTR = document.getElementById(key + "TR");
            if (channelTR == undefined)
            {
                channelTR = document.createElement("tr");
                channelTR.id = key + "TR";
                channelStatsTable.appendChild(channelTR);
            }
            if (value.hasOwnProperty("loraSignal"))
            {
                icons += "<div style=\"display: inline-block;\"><img src=\"antenna.png\" style=\"width: 18px; height: 18px;\"><progress value=\"" + value.loraSignal + "\" max=\"100\" class=\"blue\" style=\"height: 8px; width: 24px; border-radius: 0px;\"></progress></div>";
            }
            if (value.hasOwnProperty("wifiSignal"))
            {
                icons += "<div style=\"display: inline-block;\"><img src=\"antenna.png\" style=\"width: 18px; height: 18px;\"><progress value=\"" + value.wifiSignal + "\" max=\"100\" class=\"green\" style=\"height: 8px; width: 24px; border-radius: 0px;\"></progress></div>";
            }
            var pingColor = "#FFFFFF";
            if (value.ping < 50)
            {
                pingColor = "#CAE8DA"
            } else if (value.ping < 150) {
                pingColor = "#FFFCE0";
            } else {
                pingColor = "#FFCFCF";
            }
            channelTR.innerHTML = "<td><a href=\"channel.html?channel=" + key + "\">" + key + "</a></td><td>" + icons + "</td><td>" + value.members + "</td><td>" + value.rx + "</td><td>" + value.tx + "</td><td style=\"background-color: " + pingColor + ";\">" + value.ping + " ms</td>";
        }
    }
};

routeput.onconnect = function() {
    document.getElementById('serverTitle').innerHTML = "Routeput Server " + capitalize(routeput.serverHostname);
    hideAdminAuthModal();
}

window.onload = function() {
    var submitBtn = document.getElementById('adminAuthSubmit');
    var input = document.getElementById('adminAuthInput');
    var submit = function() {
        var pw = input.value;
        if (!pw) return;
        sessionStorage.setItem("routeputAdminPassword", pw);
        routeput.setChannelPassword("routeputDebug", pw);
        hideAdminAuthModal();
        routeput.connect();
    };
    if (submitBtn) submitBtn.addEventListener('click', submit);
    if (input) input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
    routeput.connect();
};

