package base

var (
	Cfg            = &ClientConfig{}
	LocalInterface = &Interface{
		Name:    "tun0",
		Ip4:     "10.8.0.2",
		Mac:     "02:00:00:00:00:01",
		Gateway: "0.0.0.0",
	}
)

type Interface struct {
	Name    string `json:"name"`
	Ip4     string `json:"ip4"`
	Mac     string `json:"mac"`
	Gateway string `json:"gateway"`
}

type ClientConfig struct {
	InterfaceName      string `json:"interface_name"`
	LogLevel           string `json:"log_level"`
	LogPath            string `json:"log_path"`
	InsecureSkipVerify bool   `json:"skip_verify"`
	CiscoCompat        bool   `json:"cisco_compat"`
	NoDTLS             bool   `json:"no_dtls"`
	AgentName          string `json:"agent_name"`
	AgentVersion       string `json:"agent_version"`
}

func init() {
	InitCfg()
}

func InitCfg() {
	Cfg.LogLevel = "Debug"
	Cfg.InsecureSkipVerify = true
	Cfg.CiscoCompat = true
	Cfg.AgentName = "AnyConnect"
	Cfg.AgentVersion = "4.10.07062"
}
