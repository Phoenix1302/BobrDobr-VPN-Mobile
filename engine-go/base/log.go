package base

import (
	"fmt"
	"log"
	"strings"
)

const (
	_Debug = iota
	_Info
	_Warn
	_Error
	_Fatal
)

var (
	baseLevel = _Debug
	levels    = map[int]string{
		_Debug: "DEBUG",
		_Info:  "INFO",
		_Warn:  "WARN",
		_Error: "ERROR",
		_Fatal: "FATAL",
	}
)

func InitLog() {
	baseLevel = logLevel2Int(Cfg.LogLevel)
}

func logLevel2Int(l string) int {
	for k, v := range levels {
		if strings.EqualFold(strings.ToLower(l), strings.ToLower(v)) {
			return k
		}
	}
	return _Debug
}

func output(l int, s ...interface{}) {
	if baseLevel > l {
		return
	}
	lvl := fmt.Sprintf("[BobrDobr_Go][%s] ", levels[l])
	log.Print(lvl + fmt.Sprintln(s...))
}

func Debug(v ...interface{}) {
	output(_Debug, v...)
}

func Info(v ...interface{}) {
	output(_Info, v...)
}

func Warn(v ...interface{}) {
	output(_Warn, v...)
}

func Error(v ...interface{}) {
	output(_Error, v...)
}

func Fatal(v ...interface{}) {
	output(_Fatal, v...)
}
